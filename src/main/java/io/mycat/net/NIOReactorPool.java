package io.mycat.net;

import java.io.IOException;

/**
 * 管理了一组NIOReactor，每个NIOReactor拥有一个selector负责处理IO监听
 * 
 * @author Administrator
 *
 */
public class NIOReactorPool {
	private final NIOReactor[] reactors;
	private volatile int nextReactor;

	public NIOReactorPool(String name, int poolSize) throws IOException {
		reactors = new NIOReactor[poolSize];
		for (int i = 0; i < poolSize; i++) {
			NIOReactor reactor = new NIOReactor(name + "-" + i);
			reactors[i] = reactor;
			reactor.startup();
		}
	}

	/**
	 * 轮询选择Reactor，实现负载均衡
	 * 
	 * <p>
	 * NIOReactorPool在ManagerAcceptor/ServerAcceptor/NIOAcceptor间共享，因此，这里肯定有并发问题，但是
	 * 在大量的操作中，也可能是负载均衡的
	 * </p>
	 * 
	 * @return
	 */
	public NIOReactor getNextReactor() {
//		if (++nextReactor == reactors.length) {
//			nextReactor = 0;
//		}
//		return reactors[nextReactor];

        int i = ++nextReactor;
        if (i >= reactors.length) {
            i=nextReactor = 0;
        }
        return reactors[i];
	}
}
