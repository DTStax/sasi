/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.index.sasi.disk;

import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.index.sasi.sa.IntegralSA;
import org.apache.cassandra.db.index.sasi.sa.SA;
import org.apache.cassandra.db.index.sasi.sa.TermIterator;
import org.apache.cassandra.db.index.sasi.sa.SuffixSA;
import org.apache.cassandra.db.marshal.*;
import org.apache.cassandra.io.FSWriteError;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.io.util.SequentialWriter;
import org.apache.cassandra.utils.ByteBufferDataOutput;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Pair;

import com.carrotsearch.hppc.LongArrayList;
import com.carrotsearch.hppc.LongSet;
import com.carrotsearch.hppc.ShortArrayList;
import com.google.common.annotations.VisibleForTesting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OnDiskIndexBuilder
{
    private static final Logger logger = LoggerFactory.getLogger(OnDiskIndexBuilder.class);

    public enum Mode
    {
        SUFFIX, ORIGINAL, SPARSE;

        public static Mode mode(String mode)
        {
            return Mode.valueOf(mode.toUpperCase());
        }
    }

    public enum TermSize
    {
        INT(4), LONG(8), UUID(16), VARIABLE(-1);

        public final int size;

        TermSize(int size)
        {
            this.size = size;
        }

        public boolean isConstant()
        {
            return this != VARIABLE;
        }

        public static TermSize of(int size)
        {
            switch (size)
            {
                case -1:
                    return VARIABLE;

                case 4:
                    return INT;

                case 8:
                    return LONG;

                case 16:
                    return UUID;

                default:
                    throw new IllegalStateException("unknown state: " + size);
            }
        }

        public static TermSize sizeOf(AbstractType<?> comparator)
        {
            if (comparator instanceof Int32Type || comparator instanceof FloatType)
                return INT;

            if (comparator instanceof LongType || comparator instanceof DoubleType
                    || comparator instanceof TimestampType || comparator instanceof DateType)
                return LONG;

            if (comparator instanceof TimeUUIDType || comparator instanceof UUIDType)
                return UUID;

            return VARIABLE;
        }
    }

    public static final int BLOCK_SIZE = 4096;
    public static final int MAX_TERM_SIZE = 1024;
    public static final int SUPER_BLOCK_SIZE = 64;

    private final List<MutableLevel<InMemoryPointerTerm>> levels = new ArrayList<>();
    private MutableLevel<InMemoryDataTerm> dataLevel;

    private final TermSize termSize;

    private final AbstractType<?> keyComparator, termComparator;

    private final Map<ByteBuffer, TokenTreeBuilder> terms;
    private final Mode mode;

    private ByteBuffer minKey, maxKey;
    private long estimatedBytes;

    public OnDiskIndexBuilder(AbstractType<?> keyComparator, AbstractType<?> comparator, Mode mode)
    {
        this.keyComparator = keyComparator;
        this.termComparator = comparator;
        this.terms = new HashMap<>();
        this.termSize = TermSize.sizeOf(comparator);
        this.mode = mode;
    }

    public OnDiskIndexBuilder add(ByteBuffer term, DecoratedKey key, long keyPosition)
    {
        if (term.remaining() >= MAX_TERM_SIZE)
        {
            logger.error("Rejecting value (value size {}, maximum size {} bytes).", term.remaining(), Short.MAX_VALUE);
            return this;
        }

        TokenTreeBuilder tokens = terms.get(term);
        if (tokens == null)
        {
            terms.put(term, (tokens = new TokenTreeBuilder()));

            // on-heap size estimates from jol
            // 64 bytes for TTB + 48 bytes for TreeMap in TTB + size bytes for the term (map key)
            estimatedBytes += 64 + 48 + term.remaining();
        }

        tokens.add((Long) key.getToken().token, keyPosition);

        // calculate key range (based on actual key values) for current index
        minKey = (minKey == null || keyComparator.compare(minKey, key.key) > 0) ? key.key : minKey;
        maxKey = (maxKey == null || keyComparator.compare(maxKey, key.key) < 0) ? key.key : maxKey;

        // 60 ((boolean(1)*4) + (long(8)*4) + 24) bytes for the LongOpenHashSet created when the keyPosition was added
        // + 40 bytes for the TreeMap.Entry + 8 bytes for the token (key).
        // in the case of hash collision for the token we may overestimate but this is extremely rare
        estimatedBytes += 60 + 40 + 8;

        return this;
    }

    public long estimatedMemoryUse()
    {
        return estimatedBytes;
    }

    private void addTerm(InMemoryDataTerm term, SequentialWriter out) throws IOException
    {
        InMemoryPointerTerm ptr = dataLevel.add(term);
        if (ptr == null)
            return;

        int levelIdx = 0;
        for (;;)
        {
            MutableLevel<InMemoryPointerTerm> level = getIndexLevel(levelIdx++, out);
            if ((ptr = level.add(ptr)) == null)
                break;
        }
    }

    public boolean isEmpty()
    {
        return terms.isEmpty();
    }

    public void finish(Pair<ByteBuffer, ByteBuffer> range, File file, TermIterator terms)
    {
        finish(Descriptor.CURRENT, range, file, terms);
    }

    /**
     * Finishes up index building process by creating/populating index file.
     *
     * @param indexFile The file to write index contents to.
     *
     * @return true if index was written successfully, false otherwise (e.g. if index was empty).
     *
     * @throws FSWriteError on I/O error.
     */
    public boolean finish(File indexFile) throws FSWriteError
    {
        return finish(Descriptor.CURRENT, indexFile);
    }

    @VisibleForTesting
    protected boolean finish(Descriptor descriptor, File file) throws FSWriteError
    {
        // no terms means there is nothing to build
        if (terms.isEmpty())
            return false;

        // split terms into suffixes only if it's text, otherwise (even if SUFFIX is set) use terms in original form
        SA sa = ((termComparator instanceof UTF8Type || termComparator instanceof AsciiType) && mode == Mode.SUFFIX)
                    ? new SuffixSA(termComparator, mode) : new IntegralSA(termComparator, mode);

        for (Map.Entry<ByteBuffer, TokenTreeBuilder> term : terms.entrySet())
            sa.add(term.getKey(), term.getValue());

        finish(descriptor, Pair.create(minKey, maxKey), file, sa.finish());
        return true;
    }

    protected void finish(Descriptor descriptor, Pair<ByteBuffer, ByteBuffer> range, File file, TermIterator terms)
    {
        SequentialWriter out = null;

        try
        {
            out = new SequentialWriter(file, BLOCK_SIZE, false);

            out.writeUTF(descriptor.version.toString());

            out.writeShort(termSize.size);

            // min, max term (useful to find initial scan range from search expressions)
            ByteBufferUtil.writeWithShortLength(terms.minTerm(), out);
            ByteBufferUtil.writeWithShortLength(terms.maxTerm(), out);

            // min, max keys covered by index (useful when searching across multiple indexes)
            ByteBufferUtil.writeWithShortLength(range.left, out);
            ByteBufferUtil.writeWithShortLength(range.right, out);

            out.writeUTF(mode.toString());

            out.skipBytes((int) (BLOCK_SIZE - out.getFilePointer()));

            dataLevel = mode == Mode.SPARSE ? new DataBuilderLevel(out, new MutableDataBlock(mode))
                                            : new MutableLevel<>(out, new MutableDataBlock(mode));
            while (terms.hasNext())
            {
                Pair<ByteBuffer, TokenTreeBuilder> term = terms.next();
                addTerm(new InMemoryDataTerm(term.left, term.right), out);
            }

            dataLevel.finalFlush();
            for (MutableLevel l : levels)
                l.flush(); // flush all of the buffers

            // and finally write levels index
            final long levelIndexPosition = out.getFilePointer();

            out.writeInt(levels.size());
            for (int i = levels.size() - 1; i >= 0; i--)
                levels.get(i).flushMetadata();

            dataLevel.flushMetadata();

            out.writeLong(levelIndexPosition);
        }
        catch (IOException e)
        {
            throw new FSWriteError(e, file);
        }
        finally
        {
            FileUtils.closeQuietly(out);
        }
    }

    private MutableLevel<InMemoryPointerTerm> getIndexLevel(int idx, SequentialWriter out)
    {
        if (levels.size() == 0)
            levels.add(new MutableLevel<>(out, new MutableBlock<InMemoryPointerTerm>()));

        if (levels.size() - 1 < idx)
        {
            int toAdd = idx - (levels.size() - 1);
            for (int i = 0; i < toAdd; i++)
                levels.add(new MutableLevel<>(out, new MutableBlock<InMemoryPointerTerm>()));
        }

        return levels.get(idx);
    }

    protected static void alignToBlock(SequentialWriter out) throws IOException
    {
        long endOfBlock = out.getFilePointer();
        if ((endOfBlock & (BLOCK_SIZE - 1)) != 0) // align on the block boundary if needed
            out.skipBytes((int) (FBUtilities.align(endOfBlock, BLOCK_SIZE) - endOfBlock));
    }

    private class InMemoryTerm
    {
        protected final ByteBuffer term;

        public InMemoryTerm(ByteBuffer term)
        {
            this.term = term;
        }

        public int serializedSize()
        {
            return (termSize.isConstant() ? 0 : 2) + term.remaining();
        }

        public void serialize(DataOutput out) throws IOException
        {
            if (termSize.isConstant())
                ByteBufferUtil.write(term, out);
            else
                ByteBufferUtil.writeWithShortLength(term, out);
        }
    }

    private class InMemoryPointerTerm extends InMemoryTerm
    {
        protected final int blockCnt;

        public InMemoryPointerTerm(ByteBuffer term, int blockCnt)
        {
            super(term);
            this.blockCnt = blockCnt;
        }

        @Override
        public int serializedSize()
        {
            return super.serializedSize() + 4;
        }

        @Override
        public void serialize(DataOutput out) throws IOException
        {
            super.serialize(out);
            out.writeInt(blockCnt);
        }
    }

    private class InMemoryDataTerm extends InMemoryTerm
    {
        private TokenTreeBuilder keys;

        public InMemoryDataTerm(ByteBuffer term, TokenTreeBuilder keys)
        {
            super(term);
            this.keys = keys;
        }
    }

    private class MutableLevel<T extends InMemoryTerm>
    {
        private final LongArrayList blockOffsets = new LongArrayList();

        protected final SequentialWriter out;

        private final MutableBlock<T> inProcessBlock;
        private InMemoryPointerTerm lastTerm;

        public MutableLevel(SequentialWriter out, MutableBlock<T> block)
        {
            this.out = out;
            this.inProcessBlock = block;
        }

        /**
         * @return If we flushed a block, return the last term of that block; else, null.
         */
        public InMemoryPointerTerm add(T term) throws IOException
        {
            InMemoryPointerTerm toPromote = null;

            if (!inProcessBlock.hasSpaceFor(term))
            {
                flush();
                toPromote = lastTerm;
            }

            inProcessBlock.add(term);

            lastTerm = new InMemoryPointerTerm(term.term, blockOffsets.size());
            return toPromote;
        }

        public void flush() throws IOException
        {
            blockOffsets.add(out.getFilePointer());
            inProcessBlock.flushAndClear(out);
        }

        public void finalFlush() throws IOException
        {
            flush();
        }

        public void flushMetadata() throws IOException
        {
            flushMetadata(blockOffsets);
        }

        protected void flushMetadata(LongArrayList longArrayList) throws IOException
        {
            out.writeInt(longArrayList.size());
            for (int i = 0; i < longArrayList.size(); i++)
                out.writeLong(longArrayList.get(i));
        }
    }

    /** builds standard data blocks and super blocks, as well */
    private class DataBuilderLevel extends MutableLevel<InMemoryDataTerm>
    {
        private final LongArrayList superBlockOffsets = new LongArrayList();

        /** count of regular data blocks written since current super block was init'd */
        private int dataBlocksCnt;
        private TokenTreeBuilder superBlockTree;

        public DataBuilderLevel(SequentialWriter out, MutableBlock<InMemoryDataTerm> block)
        {
            super(out, block);
            superBlockTree = new TokenTreeBuilder();
        }

        public InMemoryPointerTerm add(InMemoryDataTerm term) throws IOException
        {
            InMemoryPointerTerm ptr = super.add(term);
            if (ptr != null)
            {
                dataBlocksCnt++;
                flushSuperBlock(false);
            }
            superBlockTree.add(term.keys.getTokens());
            return ptr;
        }

        public void flushSuperBlock(boolean force) throws IOException
        {
            if (dataBlocksCnt == SUPER_BLOCK_SIZE || (force && !superBlockTree.getTokens().isEmpty()))
            {
                superBlockOffsets.add(out.getFilePointer());
                superBlockTree.finish().write(out);
                alignToBlock(out);

                dataBlocksCnt = 0;
                superBlockTree = new TokenTreeBuilder();
            }
        }

        public void finalFlush() throws IOException
        {
            super.flush();
            flushSuperBlock(true);
        }

        public void flushMetadata() throws IOException
        {
            super.flushMetadata();
            flushMetadata(superBlockOffsets);
        }
    }

    private static class MutableBlock<T extends InMemoryTerm>
    {
        protected final ByteBufferDataOutput buffer;
        protected final ShortArrayList offsets;

        public MutableBlock()
        {
            buffer = new ByteBufferDataOutput(ByteBuffer.allocate(BLOCK_SIZE));
            offsets = new ShortArrayList();
        }

        public final void add(T term) throws IOException
        {
            offsets.add((short) buffer.position());
            addInternal(term);
        }

        protected void addInternal(T term) throws IOException
        {
            term.serialize(buffer);
        }

        public boolean hasSpaceFor(T element)
        {
            return sizeAfter(element) < buffer.capacity();
        }

        protected int sizeAfter(T element)
        {
            return getWatermark() + 4 + element.serializedSize();
        }

        protected int getWatermark()
        {
            return 4 + offsets.size() * 2 + buffer.position();
        }

        public void flushAndClear(SequentialWriter out) throws IOException
        {
            out.writeInt(offsets.size());
            for (int i = 0; i < offsets.size(); i++)
                out.writeShort(offsets.get(i));

            buffer.writeFullyTo(out);

            alignToBlock(out);

            offsets.clear();
            buffer.clear();
        }
    }

    private static class MutableDataBlock extends MutableBlock<InMemoryDataTerm>
    {
        private final Mode mode;

        private int offset = 0;
        private int sparseValueTerms = 0;

        private final List<TokenTreeBuilder> containers = new ArrayList<>();
        private TokenTreeBuilder combinedIndex;

        public MutableDataBlock(Mode mode)
        {
            this.mode = mode;
            this.combinedIndex = new TokenTreeBuilder();
        }

        @Override
        protected void addInternal(InMemoryDataTerm term) throws IOException
        {
            TokenTreeBuilder keys = term.keys;

            if (mode == Mode.SPARSE && keys.getTokenCount() <= 5)
            {
                writeTerm(term, keys);
                sparseValueTerms++;
            }
            else
            {
                writeTerm(term, offset);

                offset += keys.serializedSize();
                containers.add(keys);
            }

            if (mode == Mode.SPARSE)
                combinedIndex.add(keys.getTokens());
        }

        @Override
        protected int sizeAfter(InMemoryDataTerm element)
        {
            return super.sizeAfter(element) + ptrLength(element);
        }

        @Override
        public void flushAndClear(SequentialWriter out) throws IOException
        {
            super.flushAndClear(out);

            out.writeInt((sparseValueTerms == 0) ? -1 : offset);

            if (containers.size() > 0)
            {
                for (TokenTreeBuilder tokens : containers)
                    tokens.write(out);
            }

            if (sparseValueTerms > 0)
            {
                combinedIndex.finish().write(out);
            }

            alignToBlock(out);

            containers.clear();
            combinedIndex = new TokenTreeBuilder();

            offset = 0;
            sparseValueTerms = 0;
        }

        private int ptrLength(InMemoryDataTerm term)
        {
            return (term.keys.getTokenCount() > 5)
                    ? 5 // 1 byte type + 4 byte offset to the tree
                    : 1 + (8 * (int) term.keys.getTokenCount()); // 1 byte size + n 8 byte tokens
        }

        private void writeTerm(InMemoryTerm term, TokenTreeBuilder keys) throws IOException
        {
            term.serialize(buffer);
            buffer.writeByte((byte) keys.getTokenCount());

            Iterator<Pair<Long, LongSet>> tokens = keys.iterator();
            while (tokens.hasNext())
                buffer.writeLong(tokens.next().left);
        }

        private void writeTerm(InMemoryTerm term, int offset) throws IOException
        {
            term.serialize(buffer);
            buffer.writeByte(0x0);
            buffer.writeInt(offset);
        }
    }
}
