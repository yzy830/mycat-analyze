package io.mycat.net;

import java.io.IOException;


/**
 * yzy: 针对某条链路完成读写操作
 *
 */
public abstract class SocketWR {
	public abstract void asynRead() throws IOException;
	public abstract void doNextWriteCheck() ;
}
