package io.netty.example.study;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.example.discard.DiscardServerHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import java.net.SocketAddress;

public class StudyHandler {
    public static void main(String[] args) throws Exception{
        /**
         *liang fix @date 2022/8/4
         *  {@link NioEventLoop#run()}
         *  {@link io.netty.channel.ChannelHandler}
         *  {@link io.netty.channel.ChannelInboundHandler}
         *  {@link io.netty.channel.ChannelPipeline}
         *  {@link io.netty.channel.ChannelDuplexHandler}
         *  {@link io.netty.channel.embedded.EmbeddedChannel}
         *  {@link io.netty.channel.ChannelInboundHandlerAdapter}
         *  {@link io.netty.channel.ChannelOutboundHandlerAdapter}
         *  {@link io.netty.channel.DefaultChannelPipeline.TailContext#bind(SocketAddress)}
         *  {@link io.netty.channel.DefaultChannelPipeline.HeadContext}
         *  {@link io.netty.bootstrap.ServerBootstrap.ServerBootstrapAcceptor}
         *  {@link NioEventLoop#register(ChannelPromise)}
         *
         *  {@link NioEventLoop#processSelectedKeys()}
         *
         *  {@link NioSocketChannel.NioSocketChannelUnsafe#read()}
         *
         *
         *  handler
         *  {@link io.netty.handler.codec.ByteToMessageDecoder}
         *  {@link io.netty.handler.codec.ReplayingDecoder}
         *  {@link io.netty.handler.codec.MessageToMessageDecoder}
         *  {@link io.netty.handler.codec.FixedLengthFrameDecoder}
         *  {@link io.netty.handler.codec.LineBasedFrameDecoder}
         *  {@link io.netty.handler.codec.DelimiterBasedFrameDecoder}
         *  {@link io.netty.handler.codec.LengthFieldBasedFrameDecoder}
         *
         *  {@link io.netty.handler.codec.MessageToByteEncoder}
         *  {@link io.netty.handler.codec.MessageToMessageEncoder}
         *
         *  {@link io.netty.handler.codec.ByteToMessageCodec}
         *
         *  {@link CombinedChannelDuplexHandler}
         *
         *  {@link io.netty.handler.codec.LengthFieldPrepender}
         *  
         */
        test1();
    }


    public void testEmbeddedChannel() throws Exception {
        ChannelInitializer<EmbeddedChannel> channelInitializer = new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel ch) throws Exception {

            }
        };
        EmbeddedChannel channel = new EmbeddedChannel(channelInitializer);
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeInt(1);

        // 2022/9/23 liang fix  模拟数据入栈
        channel.writeInbound(buffer);
        channel.flush();

        // 2022/9/23 liang fix 通道关闭
        channel.close();
        Thread.sleep(Integer.MAX_VALUE);
    }


    public static void test1() throws Exception {
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup workerGroup = new NioEventLoopGroup(1);

        try {
            ServerBootstrap server = new ServerBootstrap();
            server.channel(NioServerSocketChannel.class)
                    .group(bossGroup, workerGroup)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new LoggingHandler(LogLevel.INFO));
                            p.addLast(new DiscardServerHandler());
                        }
                    });

            ChannelFuture f = server.bind(9999).sync();
            f.channel().closeFuture().sync();

        } catch (Exception e) {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }

    }
}
