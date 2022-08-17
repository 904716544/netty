package io.netty.example.study;

import io.netty.buffer.AbstractReferenceCountedByteBuf;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.internal.ReferenceCountUpdater;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public class AtomicIntegerFieldUpdaterTest {
    private static final AtomicIntegerFieldUpdater<AbstractReferenceCountedByteBuf> AIF_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractReferenceCountedByteBuf.class, "refCnt");

    public static void main(String[] args) {
    }
}
