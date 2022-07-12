package io.netty.example.study;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

public class StudyByteBuf {
    public static void main(String[] args) {
        PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
        ByteBuf buffer1 = allocator.buffer(1024 * 9);
        buffer1.writeByte(1);
        buffer1.release();
//        ByteBuf buffer2 = allocator.buffer(1024 * 9);
//        buffer2.writeByte(2);
//        buffer2.release();

        buffer1.release();



        /**
         *   liang fix @date 2022/7/10 
         *      {@link PooledByteBufAllocator#newDirectBuffer(int, int)}
         *      {@link io.netty.buffer.PooledUnsafeDirectByteBuf}
         */

    }
}
