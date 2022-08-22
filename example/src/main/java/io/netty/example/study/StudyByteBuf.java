package io.netty.example.study;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

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

        System.setProperty("io.netty.allocator.useCacheForAllThreads","true");

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
         *
         */



        // 2022/8/6 liang fix 1.指定内存分配器,默认的实现
        PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;

        // 2022/8/6 liang fix 2.使用allocator 进行内存分配
        ByteBuf buffer = allocator.buffer(1023 * 4);
        buffer.release();

        ByteBuf buffer2 = allocator.buffer(1023 * 4);
        ByteBuf buffer3 = allocator.buffer(1023 * 4);

        buffer.writeByte(1);

        // 2022/8/6 liang fix 3.使用allocator 回收,当当前对象上引用为空时,还需要对分配的内存进行回收
        buffer.release();
        buffer2.release();

        // 2022/8/6 liang fix 4.



    }
}
