/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese 
 * opensource volunteers. you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Any questions about this component can be directed to it's project Web address 
 * https://code.google.com/p/opencloudb/.
 *
 */
package io.mycat.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannel;
import java.nio.channels.NetworkChannel;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.base.Strings;

import io.mycat.backend.mysql.CharsetUtil;
import io.mycat.util.CompressUtil;
import io.mycat.util.TimeUtil;

import org.slf4j.Logger; import org.slf4j.LoggerFactory;

/**
 * 抽象NIO连接，代表一个前台连接或者一个后台连接。
 * 
 * <p>
 * 核心方法是{@link #asynRead()}、{@link #write(byte[])}、{@link #doNextWriteCheck()}三个方法
 * <ol>
 * <li>asyncRead: 读取流数据，并提取消息，交给{@link NIOHandler#handle(byte[])}处理</li>
 * <li>write: 写入数据，首先将数据挂链，然后出发一次doNextWriteCheck</li>
 * <li>doNextWriteCheck: 首先非阻塞同步写，如果没有写完，监听write消息，由NIOReactor负责触发</li>
 * </ol>
 * <p>
 * 
 * @author mycat
 */
public abstract class AbstractConnection implements NIOConnection {
	
	protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractConnection.class);
	
	protected String host;
	protected int localPort;
	protected int port;
	protected long id;
	protected volatile String charset;
	protected volatile int charsetIndex;

	protected final NetworkChannel channel;
	protected NIOProcessor processor;
	/**
	 * 负责具体的数据处理。例如MySQLConnectionHandler、FrontentAuthenticator等。
	 */
	protected NIOHandler handler;

	protected int packetHeaderSize;
	protected int maxPacketSize;
	protected volatile ByteBuffer readBuffer;
	/**
	 * yzy: 记录上次还没有写完的ByteBuffer
	 */
	protected volatile ByteBuffer writeBuffer;
	
	/**
	 * yzy: 等待写入的数据
	 */
	protected final ConcurrentLinkedQueue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<ByteBuffer>();
	
	protected volatile int readBufferOffset;
	protected long lastLargeMessageTime;
	protected final AtomicBoolean isClosed;
	protected boolean isSocketClosed;
	protected long startupTime;
	protected long lastReadTime;
	protected long lastWriteTime;
	protected long netInBytes;
	protected long netOutBytes;
	protected int writeAttempts;
	
	protected volatile boolean isSupportCompress = false;
    protected final ConcurrentLinkedQueue<byte[]> decompressUnfinishedDataQueue = new ConcurrentLinkedQueue<byte[]>();
    protected final ConcurrentLinkedQueue<byte[]> compressUnfinishedDataQueue = new ConcurrentLinkedQueue<byte[]>();

	private long idleTimeout;

	/**
	 * yzy: SocketWR封装了具体的读写和注册操作
	 */
	private final SocketWR socketWR;

	public AbstractConnection(NetworkChannel channel) {
		this.channel = channel;
		boolean isAIO = (channel instanceof AsynchronousChannel);
		if (isAIO) {
			socketWR = new AIOSocketWR(this);
		} else {
			socketWR = new NIOSocketWR(this);
		}
		this.isClosed = new AtomicBoolean(false);
		this.startupTime = TimeUtil.currentTimeMillis();
		this.lastReadTime = startupTime;
		this.lastWriteTime = startupTime;
	}

	public String getCharset() {
		return charset;
	}

	public boolean setCharset(String charset) {

		// 修复PHP字符集设置错误, 如： set names 'utf8'
		if (charset != null) {
			charset = charset.replace("'", "");
		}

		int ci = CharsetUtil.getIndex(charset);
		if (ci > 0) {
			this.charset = charset.equalsIgnoreCase("utf8mb4") ? "utf8" : charset;
			this.charsetIndex = ci;
			return true;
		} else {
			return false;
		}
	}

	public boolean isSupportCompress() {
		return isSupportCompress;
	}

	public void setSupportCompress(boolean isSupportCompress) {
		this.isSupportCompress = isSupportCompress;
	}

	public int getCharsetIndex() {
		return charsetIndex;
	}

	public long getIdleTimeout() {
		return idleTimeout;
	}

	public SocketWR getSocketWR() {
		return socketWR;
	}

	public void setIdleTimeout(long idleTimeout) {
		this.idleTimeout = idleTimeout;
	}

	public int getLocalPort() {
		return localPort;
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public void setLocalPort(int localPort) {
		this.localPort = localPort;
	}

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public boolean isIdleTimeout() {
		return TimeUtil.currentTimeMillis() > Math.max(lastWriteTime, lastReadTime) + idleTimeout;
	}

	public NetworkChannel getChannel() {
		return channel;
	}

	public int getPacketHeaderSize() {
		return packetHeaderSize;
	}

	public void setPacketHeaderSize(int packetHeaderSize) {
		this.packetHeaderSize = packetHeaderSize;
	}

	public int getMaxPacketSize() {
		return maxPacketSize;
	}

	public void setMaxPacketSize(int maxPacketSize) {
		this.maxPacketSize = maxPacketSize;
	}

	public long getStartupTime() {
		return startupTime;
	}

	public long getLastReadTime() {
		return lastReadTime;
	}

	/**
	 * 为一个连接分配NIOProcessor(处理器)。NIOProcessor是处理器抽象，提供内存和计算资源。
	 * <p>
	 * 这里在分配NIOProcessor时，同时从BufferPool分配了一个ReadBuffer，大小是一个chunk
	 * </p>
	 * 
	 * @param processor
	 */
	public void setProcessor(NIOProcessor processor) {
		this.processor = processor;
		int size = processor.getBufferPool().getChunkSize();
		this.readBuffer = processor.getBufferPool().allocate(size);
	}

	public long getLastWriteTime() {
		return lastWriteTime;
	}

	public long getNetInBytes() {
		return netInBytes;
	}

	public long getNetOutBytes() {
		return netOutBytes;
	}

	public int getWriteAttempts() {
		return writeAttempts;
	}

	public NIOProcessor getProcessor() {
		return processor;
	}

	public ByteBuffer getReadBuffer() {
		return readBuffer;
	}

	/**
	 * 从BufferPool分配内存，每次分配一个chunk
	 * 
	 * @return
	 */
	public ByteBuffer allocate() {
		int size = this.processor.getBufferPool().getChunkSize();
		ByteBuffer buffer = this.processor.getBufferPool().allocate(size);
		return buffer;
	}

	public final void recycle(ByteBuffer buffer) {
		this.processor.getBufferPool().recycle(buffer);
	}

	public void setHandler(NIOHandler handler) {
		this.handler = handler;
	}

	/**
	 * 调用NIOHandler，处理特定业务数据
	 * 
	 * */
	@Override
	public void handle(byte[] data) {
		if (isSupportCompress()) {
		    // yzy: 这个是MYSQL的特定业务，不应该放在这里，应该由MySQLConnectionHandler来处理
			List<byte[]> packs = CompressUtil.decompressMysqlPacket(data, decompressUnfinishedDataQueue);
			for (byte[] pack : packs) {
				if (pack.length != 0) {
					handler.handle(pack);
				}
			}
		} else {
			handler.handle(data);
		}
	}

	/**
	 * 现在在注册时，没有使用这个接口。这个实现并不好，还是应该实现这个接口
	 * */
	@Override
	public void register() throws IOException {

	}

	public void asynRead() throws IOException {
		this.socketWR.asynRead();
	}

	public void doNextWriteCheck() throws IOException {
		this.socketWR.doNextWriteCheck();
	}

	/**
	 * 由SocketRW的asyncRead调用，处理读取数据
	 */
	public void onReadData(int got) throws IOException {
		
		if (isClosed.get()) {
			return;
		}
		
		lastReadTime = TimeUtil.currentTimeMillis();
		if (got < 0) {
			this.close("stream closed");
            return;
		} else if (got == 0
				&& !this.channel.isOpen()) {
				this.close("socket closed");
				return;
		}
		netInBytes += got;
		processor.addNetInBytes(got);

		// 循环处理字节信息
		int offset = readBufferOffset, length = 0, position = readBuffer.position();
		/*
		 * 整个数据读取的思路是：
		 * (1) 复用一个readBuffer，避免内存的反复申请和释放
		 * (2) 由于流数据是连续来的，因此使用readBufferOffset来处理同一段数据内有多个命令的情况。避免使用buffer#compact带来的CPU开销
		 * (3) 如果readBuffer不足以容纳数据，首先尝试compact buffer；如果空Buffer都不足以容纳消息，则根据消息体大小，重新分配一个消息。
		 *     buffer的最大长度受maxPacketSize控制，默认为16M
		 * */
		for (;;) {
			length = getPacketLength(readBuffer, offset);			
			if (length == -1) {
			    /* 没能读取到足够的信息，将buffer空间压缩之后，直接退出等待下一次读取 */
				if (offset != 0) {
				    // 这里还可以做一个判断，只在readBuffer没有剩余空间的时候，才压缩，避免不必要的数据搬移
					this.readBuffer = compactReadBuffer(readBuffer, offset);
				} else if (readBuffer != null && !readBuffer.hasRemaining()) {
				    /*
				     * 这个路径应该不会走到。当getPacketLength返回-1时，说明读取的数据量不够解析消息头；而这里，有
				     * 说明buffer已经满了，并且offset == 0。那么，说明buffer的大小不足4个字节。除非配置错误，否则
				     * 不可能走到这个路径
				     * */
					throw new RuntimeException( "invalid readbuffer capacity ,too little buffer size " 
							+ readBuffer.capacity());
				}
				break;
			}

			if (position >= offset + length && readBuffer != null) {
				/* yzy: 读取到了完整的业务数据，将数据提取出来。因为buffer中，存在多个报文，因此不能使用flip，直接操作position*/
				// handle this package
				readBuffer.position(offset);				
				byte[] data = new byte[length];
				readBuffer.get(data, 0, length);
				handle(data);
				
				// maybe handle stmt_close
				if(isClosed()) {
					return ;
				}

				// offset to next position
				offset += length;
				
				// reached end
				if (position == offset) {
					// if cur buffer is temper none direct byte buffer and not
					// received large message in recent 30 seconds
					// then change to direct buffer for performance
					if (readBuffer != null && !readBuffer.isDirect()
							&& lastLargeMessageTime < lastReadTime - 30 * 1000L) {  // used temp heap
						if (LOGGER.isDebugEnabled()) {
							LOGGER.debug("change to direct con read buffer ,cur temp buf size :" + readBuffer.capacity());
						}
						recycle(readBuffer);
						readBuffer = processor.getBufferPool().allocate(processor.getBufferPool().getConReadBuferChunk());
					} else {
						if (readBuffer != null) {
							readBuffer.clear();
						}
					}
					// no more data ,break
					readBufferOffset = 0;
					break;
				} else {
					// try next package parse
					readBufferOffset = offset;
					if(readBuffer != null) {
						readBuffer.position(position);
					}
					continue;
				}
				
				
				
			} else {				
				// not read whole message package ,so check if buffer enough and
				// compact readbuffer
				if (!readBuffer.hasRemaining()) {
					readBuffer = ensureFreeSpaceOfReadBuffer(readBuffer, offset, length);
				}
				break;
			}
		}
	}
	
	private boolean isConReadBuffer(ByteBuffer buffer) {
		return buffer.capacity() == processor.getBufferPool().getConReadBuferChunk() && buffer.isDirect();
	}
	
	private ByteBuffer ensureFreeSpaceOfReadBuffer(ByteBuffer buffer,
			int offset, final int pkgLength) {
		// need a large buffer to hold the package
		if (pkgLength > maxPacketSize) {
		    // yzy: maxPackageSize可配置，默认16M
			throw new IllegalArgumentException("Packet size over the limit.");
		} else if (buffer.capacity() < pkgLength) {
		    // 当buffer的消息不足以存放整个消息时，重新分配一个buffer
			ByteBuffer newBuffer = processor.getBufferPool().allocate(pkgLength);
			lastLargeMessageTime = TimeUtil.currentTimeMillis();
			buffer.position(offset);
			newBuffer.put(buffer);
			readBuffer = newBuffer;

			recycle(buffer);
			readBufferOffset = 0;
			return newBuffer;

		} else {
			if (offset != 0) {
				// compact bytebuffer only
				return compactReadBuffer(buffer, offset);
			} else {
				throw new RuntimeException(" not enough space");
			}
		}
	}
	
	/**
	 * 压缩ByteBuffer，丢弃offset之前的数据
	 * 
	 * @param buffer
	 * @param offset
	 * @return
	 */
	private ByteBuffer compactReadBuffer(ByteBuffer buffer, int offset) {
		if(buffer == null) {
			return null;
		}
		buffer.limit(buffer.position());
		buffer.position(offset);
		buffer = buffer.compact();
		readBufferOffset = 0;
		return buffer;
	}

	public void write(byte[] data) {
		ByteBuffer buffer = allocate();
		buffer = writeToBuffer(data, buffer);
		write(buffer);

	}

	private final void writeNotSend(ByteBuffer buffer) {
		if (isSupportCompress()) {
			ByteBuffer newBuffer = CompressUtil.compressMysqlPacket(buffer, this, compressUnfinishedDataQueue);
			writeQueue.offer(newBuffer);
			
		} else {
			writeQueue.offer(buffer);
		}
	}


	/**
	 * 将Buffer挂入writeQueue，等待异步发送。这个操作会触发一次{@link SocketWR#doNextWriteCheck()}，触发一次同步写，并注册异步写
	 * */
    @Override
	public final void write(ByteBuffer buffer) {
    	
		if (isSupportCompress()) {
			ByteBuffer newBuffer = CompressUtil.compressMysqlPacket(buffer, this, compressUnfinishedDataQueue);
			writeQueue.offer(newBuffer);
		} else {
			writeQueue.offer(buffer);
		}

		// if ansyn write finishe event got lock before me ,then writing
		// flag is set false but not start a write request
		// so we check again
		try {
			this.socketWR.doNextWriteCheck();
		} catch (Exception e) {
			LOGGER.warn("write err:", e);
			this.close("write err:" + e);
		}
	}

	
	/**
	 * 检查buffer的剩余空间是否满足capacity容量要求。如果满足，直接返回；如果不满足：
	 * <ol>
	 * <li>如果writeSocketIfFull为true，分配一个新的buffer，原来的buffer挂入writeQueue</li>
	 * <li>如果writeSocketIfFull为false，则分配一个新的buffer，将原来的buffer内容拷贝进去，然后返回</li>
	 * </ol>
	 * 
	 * @param buffer
	 * @param capacity
	 * @param writeSocketIfFull
	 * @return
	 */
	public ByteBuffer checkWriteBuffer(ByteBuffer buffer, int capacity, boolean writeSocketIfFull) {
		if (capacity > buffer.remaining()) {
			if (writeSocketIfFull) {
				writeNotSend(buffer);
				return processor.getBufferPool().allocate(capacity);
			} else {// Relocate a larger buffer
				buffer.flip();
				ByteBuffer newBuf = processor.getBufferPool().allocate(capacity + buffer.limit() + 1);
				newBuf.put(buffer);
				this.recycle(buffer);
				return newBuf;
			}
		} else {
			return buffer;
		}
	}

	/**
	 * 将src写入buffer，如果buffer空间不足，将buffer挂入writeQueue(当NIOConnector检测到数据可发送时，会从writeQueue
	 * 取出bytebuffer完成发送)，分配信息的bytebuffer继续写入数据，并返回最后一次分配的buffer
	 * 
	 * @param src
	 * @param buffer
	 * @return
	 */
	public ByteBuffer writeToBuffer(byte[] src, ByteBuffer buffer) {
		int offset = 0;
		int length = src.length;
		int remaining = buffer.remaining();
		while (length > 0) {
			if (remaining >= length) {
				buffer.put(src, offset, length);
				break;
			} else {
				buffer.put(src, offset, remaining);
				writeNotSend(buffer);
				buffer = allocate();
				offset += remaining;
				length -= remaining;
				remaining = buffer.remaining();
				continue;
			}
		}
		return buffer;
	}

	@Override
	public void close(String reason) {
		if (!isClosed.get()) {
			closeSocket();
			isClosed.set(true);
			if (processor != null) {
				processor.removeConnection(this);
			}
			this.cleanup();
			isSupportCompress = false;

			// ignore null information
			if (Strings.isNullOrEmpty(reason)) {
				return;
			}
			LOGGER.info("close connection,reason:" + reason + " ," + this);
			if (reason.contains("connection,reason:java.net.ConnectException")) {
				throw new RuntimeException(" errr");
			}
		}
	}

	public boolean isClosed() {
		return isClosed.get();
	}

	/**
	 * 这个方法命名不好，不仅检查了是否idle，在idle情况下会关闭连接
	 * */
	public void idleCheck() {
		if (isIdleTimeout()) {
			LOGGER.info(toString() + " idle timeout");
			close(" idle ");
		}
	}

	/**
	 * 清理资源
	 */
	protected void cleanup() {
		
		// 清理资源占用
		if (readBuffer != null) {
			this.recycle(readBuffer);
			this.readBuffer = null;
			this.readBufferOffset = 0;
		}
		
		if (writeBuffer != null) {
			recycle(writeBuffer);
			this.writeBuffer = null;
		}
		
		if (!decompressUnfinishedDataQueue.isEmpty()) {
			decompressUnfinishedDataQueue.clear();
		}
		
		if (!compressUnfinishedDataQueue.isEmpty()) {
			compressUnfinishedDataQueue.clear();
		}
		
		ByteBuffer buffer = null;
		while ((buffer = writeQueue.poll()) != null) {
			recycle(buffer);
		}
	}
	
	/**
	 * 解析报文长度。返回信息 = 消息头长度 + 消息长度 
	 * 
	 * @param buffer
	 *         报文buffer
	 * @param offset
	 *         偏移量
	 *         
	 * @return -1表示读取的数据量不足以解析报文长度
	 */
	protected int getPacketLength(ByteBuffer buffer, int offset) {
		int headerSize = getPacketHeaderSize();
		if ( isSupportCompress() ) {
			headerSize = 7;
		}
		
		if (buffer.position() < offset + headerSize) {
			return -1;
		} else {
		    // 这里是严格按照MYSQL的协议来执行的，MYSQL报文头前三个字节是报文长度
			int length = buffer.get(offset) & 0xff;
			length |= (buffer.get(++offset) & 0xff) << 8;
			length |= (buffer.get(++offset) & 0xff) << 16;
			// 报文头的报文长度，没有包含报文头。这里加上报文头的长度，得到整个报文的大小
			return length + headerSize;
		}
	}

	public ConcurrentLinkedQueue<ByteBuffer> getWriteQueue() {
		return writeQueue;
	}

	private void closeSocket() {
		if (channel != null) {
			
			boolean isSocketClosed = true;
			try {
				channel.close();
			} catch (Exception e) {
				LOGGER.error("AbstractConnectionCloseError", e);
			}
			
			boolean closed = isSocketClosed && (!channel.isOpen());
			if (closed == false) {
				LOGGER.warn("close socket of connnection failed " + this);
			}
		}
	}
	public void onConnectfinish() {
		LOGGER.debug("连接后台真正完成");
	}	
}
