package io.netty.example.study;

import io.netty.channel.EventLoopGroup;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
/**
 *   liang fix @date 2022/7/17
 *
 *  {@link io.netty.util.concurrent.AbstractEventExecutorGroup}
 *  {@link io.netty.util.concurrent.DefaultEventExecutor}
 *  {@link io.netty.channel.nio.NioEventLoopGroup}
 *  {@link io.netty.channel.nio.NioEventLoop}
 *  {@link io.netty.util.concurrent.AbstractEventExecutorGroup}
 */
public class StudyThread {
    public static void main(String[] args) throws Exception{

        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<String> submit = executor.submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                return "abc";
            }
        });



    }
}
