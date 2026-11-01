package com.github.serezhka.airplay.server.internal;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

/** Netty 4.1 event-loop factory. Android has no epoll transport, so NIO only. */
final class NettyEventLoops {

    private NettyEventLoops() {
    }

    static EventLoopGroup create() {
        return new NioEventLoopGroup();
    }
}
