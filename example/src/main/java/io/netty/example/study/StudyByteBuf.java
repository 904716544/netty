package io.netty.example.study;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import sun.security.action.GetBooleanAction;

import java.util.concurrent.atomic.AtomicLong;

public class StudyByteBuf {
    volatile int x = 1;

    public static void main(String[] args) {
//        PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
//        ByteBuf buffer1 = allocator.buffer(1024 * 9);
//        buffer1.writeByte(1);
//        buffer1.release();
////        ByteBuf buffer2 = allocator.buffer(1024 * 9);
////        buffer2.writeByte(2);
////        buffer2.release();
//
//        buffer1.release();

        System.setProperty("io.netty.allocator.useCacheForAllThreads", "true");

        /**
         *   liang fix @date 2022/7/10 
         *      {@link PooledByteBufAllocator#newDirectBuffer(int, int)}
         *      {@link io.netty.buffer.PooledUnsafeDirectByteBuf}
         *      {@link io.netty.util.ReferenceCounted}
         *      {@link ByteBuf}
         *      {@link io.netty.buffer.AbstractByteBuf}
         *      {@link io.netty.buffer.AbstractReferenceCountedByteBuf}
         *      {@link io.netty.buffer.PooledByteBuf}
         *      {@link io.netty.buffer.ByteBufAllocatorMetricProvider}
         *      {@link io.netty.buffer.SizeClasses}
         *      {@link io.netty.buffer.PoolArena}
         *
         *      {@link io.netty.util.internal.Cleaner}
         *
         */


        // 2022/8/6 liang fix 1.指定内存分配器,默认的实现
        PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;

        // 2022/8/6 liang fix 2.使用allocator 进行内存分配
        ByteBuf buffer = allocator.buffer(511 * 1);
        buffer.release();

        ByteBuf buffer2 = allocator.buffer(511 * 1);
        ByteBuf buffer3 = allocator.buffer(511 * 1);
        allocator.buffer(511 * 1);
        allocator.buffer(511 * 1);
        allocator.buffer(511 * 1);
        allocator.buffer(511 * 1);
        allocator.buffer(511 * 1);
        allocator.buffer(511 * 1);

        buffer.writeByte(1);

        // 2022/8/6 liang fix 3.使用allocator 回收,当当前对象上引用为空时,还需要对分配的内存进行回收
        buffer2.release();

        // 2022/8/6 liang fix 4.

        System.out.println("========== handle 分配情况    ==============");
        long initHandle = 8796093022208L;
        printHandle(initHandle);


//        printHandle(25769803776L);
//        printHandle(30064771072L);
//        printHandle(30064771073L);
//        printHandle(571728866574336L);
        printHandle(30064771072L);
        printHandle(30064771073L);
        printHandle(30064771074L);
        printHandle(30064771075L);
        printHandle(30064771076L);
        printHandle(30064771077L);
        printHandle(30064771078L);
        printHandle(30064771079L);
        printHandle(30064771080L);

    }

    public static void printHandle(long handle) {
        String bitHandle = Long.toBinaryString(handle);
        int fillZero = 64 - bitHandle.length();
        StringBuffer stringBuffer = new StringBuffer();
        for ( int i =0; i < fillZero; i++){
            stringBuffer.append("0");
        }
        String fillBitHandle = stringBuffer.append(bitHandle).toString();

        System.out.println("pageOffset " + fillBitHandle.substring(0,15));
        System.out.println("pageSize " + fillBitHandle.substring(15,30));
        System.out.println("isUsed " + fillBitHandle.substring(30,31));
        System.out.println("isSubPage " + fillBitHandle.substring(31,32));
        System.out.println("bitMapSubpageSize " + fillBitHandle.substring(32,64));

        System.out.println();
//        System.out.println(Long.toBinaryString(bitMapSubpageSize));
//        System.out.println(Long.toBinaryString(isSubPage));
//        System.out.println(Long.toBinaryString(isUsed));
//        System.out.println(Long.toBinaryString(pageSize));


    }


}
