package io.mycat.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import io.mycat.util.TimeUtil;

public class NIOSocketWR extends SocketWR {
	private SelectionKey processKey;
	private static final int OP_NOT_READ = ~SelectionKey.OP_READ;
	private static final int OP_NOT_WRITE = ~SelectionKey.OP_WRITE;
	private final AbstractConnection con;
	private final SocketChannel channel;
	private final AtomicBoolean writing = new AtomicBoolean(false);

	public NIOSocketWR(AbstractConnection con) {
		this.con = con;
		this.channel = (SocketChannel) con.channel;
	}

	/**
	 * 这个接口当前并没有实现。这是设计上的一个缺陷，在NIOConnector操作中，直接获取
	 * {@link AbstractConnection#channel}，并强转为SocketChannel，
	 * 执行register操作
	 * 
	 * @param selector
	 * @throws IOException
	 */
	public void register(Selector selector) throws IOException {
		try {
			processKey = channel.register(selector, SelectionKey.OP_READ, con);
		} finally {
			if (con.isClosed.get()) {
				clearSelectionKey();
			}
		}
	}

	/**
	 * doNextWriteCheck会首先尝试非阻塞同步写入。当writebuffer慢的时候，注册监听写事件，完成异步写操作。
	 * <p>
	 * 这里的同步操作非常精细，需要再分析。主要是要保证，不会存在writeQuery有数据，但是却没有监听写事件的的情况
	 * </p>
	 * */
	public void doNextWriteCheck() {

		if (!writing.compareAndSet(false, true)) {
			return;
		}

		try {
			boolean noMoreData = write0();
			writing.set(false);
			if (noMoreData && con.writeQueue.isEmpty()) {
				if ((processKey.isValid() && (processKey.interestOps() & SelectionKey.OP_WRITE) != 0)) {
				    // 数据已经写完，不在监听写事件
					disableWrite();
				}

			} else {

				if ((processKey.isValid() && (processKey.interestOps() & SelectionKey.OP_WRITE) == 0)) {
				    // 没有写完，继续订阅写事件
					enableWrite(false);
				}
			}

		} catch (IOException e) {
			if (AbstractConnection.LOGGER.isDebugEnabled()) {
				AbstractConnection.LOGGER.debug("caught err:", e);
			}
			con.close("err:" + e);
		}

	}

	/**
	 * 这个操作执行实际的写业务。
	 * <ol>
	 *     <li>首先写writeBuffer，这个上次没有写完bytebuffer</li>
	 *     <li>然后继续写入队列中的writebuffer</li>
	 * </ol>
	 * 
	 * @return true，数据已经写完；false，数据没有写完
	 * @throws IOException
	 */
	private boolean write0() throws IOException {

		int written = 0;
		ByteBuffer buffer = con.writeBuffer;
		if (buffer != null) {
			while (buffer.hasRemaining()) {
			    // channel是非阻塞的，在写入的时候，如果socket的output buffer已经满了，就会立刻返回，此时ByteBuffer数据可能还没有写完
			    // 这个循环持续写入，直到所有数据写完或者写不如任何数据
				written = channel.write(buffer);
				if (written > 0) {
					con.netOutBytes += written;
					con.processor.addNetOutBytes(written);
					con.lastWriteTime = TimeUtil.currentTimeMillis();
				} else {
					break;
				}
			}

			if (buffer.hasRemaining()) {
				con.writeAttempts++;
				return false;
			} else {
				con.writeBuffer = null;
				con.recycle(buffer);
			}
		}
		while ((buffer = con.writeQueue.poll()) != null) {
			if (buffer.limit() == 0) {
				con.recycle(buffer);
				con.close("quit send");
				return true;
			}

			buffer.flip();
			while (buffer.hasRemaining()) {
				written = channel.write(buffer);
				if (written > 0) {
					con.lastWriteTime = TimeUtil.currentTimeMillis();
					con.netOutBytes += written;
					con.processor.addNetOutBytes(written);
					con.lastWriteTime = TimeUtil.currentTimeMillis();
				} else {
					break;
				}
			}
			if (buffer.hasRemaining()) {
				con.writeBuffer = buffer;
				con.writeAttempts++;
				return false;
			} else {
				con.recycle(buffer);
			}
		}
		return true;
	}

	private void disableWrite() {
		try {
			SelectionKey key = this.processKey;
			key.interestOps(key.interestOps() & OP_NOT_WRITE);
		} catch (Exception e) {
			AbstractConnection.LOGGER.warn("can't disable write " + e + " con "
					+ con);
		}

	}

	private void enableWrite(boolean wakeup) {
		boolean needWakeup = false;
		try {
			SelectionKey key = this.processKey;
			key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
			needWakeup = true;
		} catch (Exception e) {
			AbstractConnection.LOGGER.warn("can't enable write " + e);

		}
		if (needWakeup && wakeup) {
			processKey.selector().wakeup();
		}
	}

	public void disableRead() {

		SelectionKey key = this.processKey;
		key.interestOps(key.interestOps() & OP_NOT_READ);
	}

	public void enableRead() {

		boolean needWakeup = false;
		try {
			SelectionKey key = this.processKey;
			key.interestOps(key.interestOps() | SelectionKey.OP_READ);
			needWakeup = true;
		} catch (Exception e) {
			AbstractConnection.LOGGER.warn("enable read fail " + e);
		}
		if (needWakeup) {
			processKey.selector().wakeup();
		}
	}

	private void clearSelectionKey() {
		try {
			SelectionKey key = this.processKey;
			if (key != null && key.isValid()) {
				key.attach(null);
				key.cancel();
			}
		} catch (Exception e) {
			AbstractConnection.LOGGER.warn("clear selector keys err:" + e);
		}
	}

	/**
	 * 使用{@link AbstractConnection#readBuffer}从channel读取数据，然后交给
	 * {@link AbstractConnection#onReadData(int)}处理
	 * 
	 * */
	@Override
	public void asynRead() throws IOException {
		ByteBuffer theBuffer = con.readBuffer;
		if (theBuffer == null) {

			theBuffer = con.processor.getBufferPool().allocate(con.processor.getBufferPool().getChunkSize());

			con.readBuffer = theBuffer;
		}

		int got = channel.read(theBuffer);

		con.onReadData(got);
	}

}
