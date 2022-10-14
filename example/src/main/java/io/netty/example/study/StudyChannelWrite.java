package io.netty.example.study;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
/**
 * liang fix @date 2022/10/14
 *  {@link io.netty.buffer.PooledUnsafeDirectByteBuf#touch()}
 *  {@link io.netty.channel.ChannelHandlerContext}
 *  {@link io.netty.channel.DefaultChannelPipeline.HeadContext#invokeWrite(Object, ChannelPromise)}
 */
import java.net.InetSocketAddress;

public class StudyChannelWrite {
    public static void main(String[] args) throws Exception {
        NioEventLoopGroup loopGroup = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap
                .group(loopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
//                        pipeline.addLast(new StringDecoder());
                        pipeline.addLast(new StringEncoder());
                    }
                });

        ChannelFuture f = bootstrap.connect(new InetSocketAddress(9999)).sync();
        for (int i = 0; i < 10; i++) {
//            f.channel().write("aaa\n");
            f.channel().writeAndFlush("abc\n");
        }

        f.channel().closeFuture().sync();

    }
}
