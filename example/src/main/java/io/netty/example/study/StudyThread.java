package io.netty.example.study;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * liang fix @date 2022/7/17
 * <p>
 * {@link io.netty.util.concurrent.AbstractEventExecutorGroup}
 * {@link io.netty.util.concurrent.DefaultEventExecutor}
 * {@link io.netty.channel.nio.NioEventLoopGroup}
 * {@link io.netty.channel.nio.NioEventLoop}
 * {@link io.netty.util.concurrent.AbstractEventExecutorGroup}
 */
public class StudyThread {
    public static void main(String[] args) throws Exception {

//        ExecutorService executor = Executors.newSingleThreadExecutor();

//        Future<String> submit = executor.submit(new Callable<String>() {
//            @Override
//            public String call() throws Exception {
//                return "abc";
//            }
//        });




        /**
         * liang fix @date 2022/10/20
         *  {@link io.netty.util.concurrent.FastThreadLocal}
         *
         *  {@link NioEventLoopGroup#NioEventLoopGroup()}
         *
         *  {@link DefaultThreadFactory}
         *  {@link io.netty.util.concurrent.ThreadPerTaskExecutor}
         *  {@link io.netty.util.concurrent.FastThreadLocalRunnable}
         *
         */

       final FastThreadLocal<String> threadLocal = new FastThreadLocal<String>() {
            @Override
            protected String initialValue() {
                return "abc";
            }
           @Override
           // 2022/10/21 liang fix 这里可以添加一些用户监控啥的
           protected void onRemoval(String value) throws Exception {
               super.onRemoval(value);
           }
       };

//        Boolean ifExists = threadLocal.getIfExists();
//        System.out.println(ifExists);
//        System.out.println(threadLocal.get());
//        assertTrue(threadLocal.getIfExists());

//        FastThreadLocal.removeAll()
//        assertNull(threadLocal.getIfExists());


        FastThreadLocalThread fastThreadLocalThread = new FastThreadLocalThread(new Runnable() {
            @Override
            public void run() {
                String s = threadLocal.get();
                threadLocal.set("ddd");
                System.out.println(threadLocal.get());
                threadLocal.remove();
            }
        });

        fastThreadLocalThread.join();
        fastThreadLocalThread.start();
    }
}
