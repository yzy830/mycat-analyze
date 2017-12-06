package io.mycat.buffer;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用来保存一个一个ByteBuffer为底层存储的内存页。
 * 
 * <p>
 * mycat的内存管理思想是，将Buffer Pool分为页。每一页是一段连续的存储空间。分配的基本单元是chunk，一个也包含多个chunk。
 * </p>
 * 
 * <p>
 * 页相对比较大，在默认情况下，一页是2048KB；一个chunk的大小是4KB。一个page包含512个chunk。
 * </p>
 * 
 * <p>
 * 以一个固定的粒度，chunk，来分配和释放空间，是为了加速内存分配和释放的过程。连续内存空间分配管理比较复杂，也容易产生内存碎片
 * </p>
 * 
 * <p>
 * 将chunk组织为页，从对外分配，目的是在分配多个chunk的空间时，可以分配连续的内存，方便操作。
 * </p>
 */
@SuppressWarnings("restriction")
public class ByteBufferPage {

    private final ByteBuffer buf;
    private final int chunkSize;
    private final int chunkCount;
    /**
     * 用位图来跟踪哪些chunk被使用了
     */
    private final BitSet chunkAllocateTrack;
    private final AtomicBoolean allocLockStatus = new AtomicBoolean(false);
    private final long startAddress;

    public ByteBufferPage(ByteBuffer buf, int chunkSize) {
        super();
        this.chunkSize = chunkSize;
        chunkCount = buf.capacity() / chunkSize;
        chunkAllocateTrack = new BitSet(chunkCount);
        this.buf = buf;
        startAddress = ((sun.nio.ch.DirectBuffer) buf).address();
    }

    /**
     * 分配指定chunk单位的存储空间，要求空间时连续的。
     * 
     * <p>
     * 需要注意的是，在分配空间时，使用了CMS操作来解决并发问题。当两个线程同时执行allocateChunk时，其中一个会得到null，
     * 即时此时尚有内存。这个方法，最好在CMS失败时，调用一次Thread.yield()，然后重试。上层在收到null时，无法区分是
     * 内存不足还是并发失败
     * </p>
     * 
     * @param theChunkCount
     *          chunk数量
     *          
     * @return 通过pageBuffer.slice获得的buffer，与Page的byteBuffer共享内存段
     */
    public ByteBuffer allocatChunk(int theChunkCount) {
        if (!allocLockStatus.compareAndSet(false, true)) {
            return null;
        }
        int startChunk = -1;
        int contiueCount = 0;
        try {
            for (int i = 0; i < chunkCount; i++) {
                if (chunkAllocateTrack.get(i) == false) {
                    if (startChunk == -1) {
                        startChunk = i;
                        contiueCount = 1;
                        if (theChunkCount == 1) {
                            break;
                        }
                    } else {
                        if (++contiueCount == theChunkCount) {
                            break;
                        }
                    }
                } else {
                    startChunk = -1;
                    contiueCount = 0;
                }
            }
            if (contiueCount == theChunkCount) {
                int offStart = startChunk * chunkSize;
                int offEnd = offStart + theChunkCount * chunkSize;
                buf.limit(offEnd);
                buf.position(offStart);

                // 生成一个新的ByteBuffer，与page的bytebuffer共享[offStart, offEnd)之间内存空间
                ByteBuffer newBuf = buf.slice();
                //sun.nio.ch.DirectBuffer theBuf = (DirectBuffer) newBuf;
                //System.out.println("offAddress " + (theBuf.address() - startAddress));
                markChunksUsed(startChunk, theChunkCount);
                return newBuf;
            } else {
                //System.out.println("contiueCount " + contiueCount + " theChunkCount " + theChunkCount);
                return null;
            }
        } finally {
            allocLockStatus.set(false);
        }
    }

    private void markChunksUsed(int startChunk, int theChunkCount) {
        for (int i = 0; i < theChunkCount; i++) {
            chunkAllocateTrack.set(startChunk + i);
        }
    }

    private void markChunksUnused(int startChunk, int theChunkCount) {
        for (int i = 0; i < theChunkCount; i++) {
            chunkAllocateTrack.clear(startChunk + i);
        }
    }

    /**
     * 回收内存。回收内存时，也需要使用CMS，但是，在recycleBuffer在失败时，会自动重试
     * 
     * @param parent
     *          拥有这些chunk的ByteBuffer
     * @param startChunk
     *          chunk的起始便宜
     * @param chunkCount
     *          chunk的数量
     *          
     * @return true，释放成功；false，要释放的chunk不属于这个page
     */
    public boolean recycleBuffer(ByteBuffer parent, int startChunk, int chunkCount) {

        if (parent == this.buf) {

            while (!this.allocLockStatus.compareAndSet(false, true)) {
                Thread.yield();
            }
            try {
                markChunksUnused(startChunk,chunkCount);
            } finally {
                allocLockStatus.set(false);
            }
            return true;
        }
        return false;
    }
}
