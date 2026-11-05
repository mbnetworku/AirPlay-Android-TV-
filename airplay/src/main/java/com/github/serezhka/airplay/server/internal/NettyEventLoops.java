package com.github.serezhka.airplay.server.internal;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

/** Netty 4.1 event-loop factory. Android has no epoll transport, so NIO only. */
final class NettyEventLoops {

    /**
     * Netty's default is 2 threads per CPU core for every group, and the receiver creates six
     * groups. A TV box only ever has one phone connected, so two threads per group is plenty:
     * boss and UDP groups only ever use one, and the control/video workers rarely more than two
     * connections at once. Threads still start lazily, so an idle receiver uses almost nothing.
     */
    private static final int THREADS_PER_GROUP = 2;

    private NettyEventLoops() {
    }

    static EventLoopGroup create() {
        return new NioEventLoopGroup(THREADS_PER_GROUP);
    }
}
