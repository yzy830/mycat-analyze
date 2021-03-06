package io.mycat.buffer;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sun.nio.ch.DirectBuffer;

/**
 * DirectByteBuffer池，可以分配任意指定大小的DirectByteBuffer，用完需要归还
 * @author wuzhih
 * @author zagnix
 */
@SuppressWarnings("restriction")
public class DirectByteBufferPool implements BufferPool{
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectByteBufferPool.class);
    public static final String LOCAL_BUF_THREAD_PREX = "$_";
    private ByteBufferPage[] allPages;
    private final int chunkSize;
   // private int prevAllocatedPage = 0;
    private AtomicInteger prevAllocatedPage;
    private final  int pageSize;
    private final short pageCount;
    private final int conReadBuferChunk ;
    /**
     * 记录对线程ID->该线程的所使用Direct Buffer的size
     */
    private final ConcurrentHashMap<Long,Long> memoryUsage;

    public DirectByteBufferPool(int pageSize, short chunkSize, short pageCount,int conReadBuferChunk) {
        allPages = new ByteBufferPage[pageCount];
        this.chunkSize = chunkSize;
        this.pageSize = pageSize;
        this.pageCount = pageCount;
        this.conReadBuferChunk = conReadBuferChunk;
        prevAllocatedPage = new AtomicInteger(0);
        for (int i = 0; i < pageCount; i++) {
            allPages[i] = new ByteBufferPage(ByteBuffer.allocateDirect(pageSize), chunkSize);
        }
        memoryUsage = new ConcurrentHashMap<>();
    }

    public BufferArray allocateArray() {
        return new BufferArray(this);
    }
    /**
     * TODO 当页不够时，考虑扩展内存池的页的数量...........
     * @param buffer
     * @return
     */
    public  ByteBuffer expandBuffer(ByteBuffer buffer){
        int oldCapacity = buffer.capacity();
        int newCapacity = oldCapacity << 1;
        ByteBuffer newBuffer = allocate(newCapacity);
        if(newBuffer != null){
            int newPosition = buffer.position();
            buffer.flip();
            newBuffer.put(buffer);
            newBuffer.position(newPosition);
            recycle(buffer);
            return  newBuffer;
        }
        return null;
    }

    /**
     * 分配指定大小的ByteBuffer。
     * 
     * <p>
     * 这个方法有待优化。每次取遍历page来分配空间，浪费CPU。应该在preAllocatedPage.incrementAndGet() % allPages.length
     * 上分配内存，如果分配失败，再重试。
     * <p>
     * 
     * <p>
     * {@link ByteBufferPage#allocatChunk(int)}在分配时，如果遇到并发，应该重试，不应该直接失败。
     * </p>
     * 
     * <p>
     * 满足以上两点，才能快速分配空间
     * </p>
     * */
    public ByteBuffer allocate(int size) {
       final int theChunkCount = size / chunkSize + (size % chunkSize == 0 ? 0 : 1);
        int selectedPage =  prevAllocatedPage.incrementAndGet() % allPages.length;
        /*
         * 这里的两条语句应该调转顺序
         * ByteBuffer byteBuf = allocateBuffer(theChunkCount, selectedPage, allPages.length);
         * if (byteBuf == null) {
         *     byteBuf = allocateBuffer(theChunkCount, 0, selectedPage);
         * }
         * 按照现在的写法，会始终先分配page 0上的空间。 
         * */
        ByteBuffer byteBuf = allocateBuffer(theChunkCount, 0, selectedPage);
        if (byteBuf == null) {
            byteBuf = allocateBuffer(theChunkCount, selectedPage, allPages.length);
        }
        final long threadId = Thread.currentThread().getId();

        if(byteBuf !=null){
            if (memoryUsage.containsKey(threadId)){
                memoryUsage.put(threadId,memoryUsage.get(threadId)+byteBuf.capacity());
            }else {
                memoryUsage.put(threadId,(long)byteBuf.capacity());
            }
        }
        return byteBuf;
    }

    /**
     * 释放buffer
     * */
    public void recycle(ByteBuffer theBuf) {
    	if(!(theBuf instanceof DirectBuffer)){
    		theBuf.clear();
    		return;
    	}

    	final long size = theBuf.capacity();

        boolean recycled = false;
        DirectBuffer thisNavBuf = (DirectBuffer) theBuf;
        int chunkCount = theBuf.capacity() / chunkSize;
        // 在使用slice方法生成新的ByteBuffer时，新的ByteBuffer的attachment记录了原来的ByteBuffer
        DirectBuffer parentBuf = (DirectBuffer) thisNavBuf.attachment();
        int startChunk = (int) ((thisNavBuf.address() - parentBuf.address()) / this.chunkSize);
        /*
         * 这里每次遍历allPages来尝试释放不好。最好是写一个CopyOnWriteMap来做映射
         * */
        for (int i = 0; i < allPages.length; i++) {
            if ((recycled = allPages[i].recycleBuffer((ByteBuffer) parentBuf, startChunk, chunkCount) == true)) {
                break;
            }
        }
        final long threadId = Thread.currentThread().getId();

        if (memoryUsage.containsKey(threadId)){
            memoryUsage.put(threadId,memoryUsage.get(threadId)-size);
        }
        if (recycled == false) {
            LOGGER.warn("warning ,not recycled buffer " + theBuf);
        }
    }

    private ByteBuffer allocateBuffer(int theChunkCount, int startPage, int endPage) {
        for (int i = startPage; i < endPage; i++) {
            ByteBuffer buffer = allPages[i].allocatChunk(theChunkCount);
            if (buffer != null) {
                prevAllocatedPage.getAndSet(i);
                return buffer;
            }
        }
        return null;
    }

    public int getChunkSize() {
        return chunkSize;
    }
	
	 @Override
    public ConcurrentHashMap<Long,Long> getNetDirectMemoryUsage() {
        return memoryUsage;
    }

    public int getPageSize() {
        return pageSize;
    }

    public short getPageCount() {
        return pageCount;
    }

    //TODO   should  fix it
    public long capacity(){
        return size();
    }

    /**
     * 这个方法算错了把。应该是pageSize * pageCount。
     * */
    public long size(){
        return  (long) pageSize * chunkSize * pageCount;
    }

    //TODO
    public  int getSharedOptsCount(){
        return 0;
    }


    public int getConReadBuferChunk() {
        return conReadBuferChunk;
    }

}
