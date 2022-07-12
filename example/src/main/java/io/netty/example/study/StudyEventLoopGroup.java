package io.netty.example.study;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

public class StudyEventLoopGroup {
    public static void main(String[] args) {
        EventLoopGroup group = new NioEventLoopGroup(1);
    }
}
