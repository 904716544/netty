package io.netty.example.study;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 *   liang fix @date 2022/7/23
 *      {@link io.netty.channel.socket.nio.NioServerSocketChannel}
 *      {@link io.netty.channel.socket.nio.NioSocketChannel}
 */
public class StudyChannel {
    public static void main(String[] args) throws Exception{
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup workGroup = new NioEventLoopGroup();

        ServerBootstrap serverBootstrap = new ServerBootstrap();

        serverBootstrap.group(bossGroup,workGroup)
                .channel(NioServerSocketChannel.class)
                .localAddress(9999)
                ;


        ChannelFuture sync = serverBootstrap.bind().sync();
        ChannelFuture channelFuture = sync.channel().closeFuture();

        bossGroup.shutdownGracefully();
        workGroup.shutdownGracefully();

    }
}
