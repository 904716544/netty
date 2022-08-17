package io.netty.example.study;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

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
         *
         */



        // 2022/8/6 liang fix 1.指定内存分配器,默认的实现
        PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;

        // 2022/8/6 liang fix 2.使用allocator 进行内存分配
        ByteBuf buffer = allocator.buffer(1024);
        buffer.writeByte(1);

        // 2022/8/6 liang fix 3.使用allocator 回收
        buffer.release();


        // 2022/8/6 liang fix 4.



    }
}
