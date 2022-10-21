package io.netty.example.study;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;

import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * liang fix @date 2022/10/21
 * netty 中的时间轮算法,调度定时任务
 *
 */
public class StudyHashedWheelTimer {

    public static void main(String[] args) throws Exception {
//        test01();
        test02();
    }

    public static void test02() throws Exception {
        Timer timer = new HashedWheelTimer();

        Timeout timeout1 = timer.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) {
                System.out.println("timeout1: " + new Date());
            }
        }, 10, TimeUnit.SECONDS);

        if (!timeout1.isExpired()) {
            timeout1.cancel();
        }

        timer.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws InterruptedException {
                System.out.println("timeout2: " + new Date());
                Thread.sleep(5000);
            }

        }, 1, TimeUnit.SECONDS);

        timer.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) {
                System.out.println("timeout3: " + new Date());
            }
        }, 3, TimeUnit.SECONDS);


        timer.stop();
    }


    //  jdk中使用的定时任务调度方式
    public static void test01() throws Exception {
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(5);
        // 1s 延迟后开始执行任务，每 2s 重复执行一次
        executor.scheduleAtFixedRate(
                new Runnable() {
                    @Override
                    public void run() {
                        System.out.println("Hello World");
                    }
                },
                1000,
                2000,
                TimeUnit.MILLISECONDS);


    }
}
