/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.util.internal.ReferenceCountUpdater;

/**
 * Abstract base class for {@link ByteBuf} implementations that count references.
 */
public abstract class AbstractReferenceCountedByteBuf extends AbstractByteBuf {

    // 2022/8/6 liang fix  refCnt字段的内存地址偏移量
    private static final long REFCNT_FIELD_OFFSET =
            ReferenceCountUpdater.getUnsafeOffset(AbstractReferenceCountedByteBuf.class, "refCnt");

    // 2022/7/10 liang fix AtomicIntegerFieldUpdater 这个类是JDK自带的类,功能是通过反射的机制获取一个类中int类型的属性进行操作
    //                      refCnt字段的原子更新器
    private static final AtomicIntegerFieldUpdater<AbstractReferenceCountedByteBuf> AIF_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractReferenceCountedByteBuf.class, "refCnt");

    /**
     *   liang fix @date 2022/7/10 重要    委托ReferenceCountUpdater实现ReferenceCounted
     *      {@link ReferenceCountUpdater} 的基本实现
     *      实现了 unsafeOffset() &  updater()
     *
     */
    private static final ReferenceCountUpdater<AbstractReferenceCountedByteBuf> updater =
            new ReferenceCountUpdater<AbstractReferenceCountedByteBuf>() {
        @Override
        protected AtomicIntegerFieldUpdater<AbstractReferenceCountedByteBuf> updater() {
            return AIF_UPDATER;
        }
        @Override
        protected long unsafeOffset() {
            return REFCNT_FIELD_OFFSET;
        }
    };

    // Value might not equal "real" reference count, all access should be via the updater
    @SuppressWarnings({"unused", "FieldMayBeFinal"})
    /**
     * liang fix @date 2022/8/6
     *      这里使用 volatile来修饰 refCnt,这里优化成了再构造器中通过unsafe类进行初始值的设置,初始值为2
     *      https://github.com/szhnet/blog/issues/1
     *      使用2是netty在更新 refCnt 时使用的是 getAndAdd() 方法
     *      这个方式是先修改,当发现不对时进行回滚,
     *      普通的方式进行更新(+1)弊端: 当当前引用数是1时
     *          1.线程1调用obj.release()，修改refCnt为0，此时obj应该被进一步执行 deallocate
     *          2.线程2调用obj.retain()，修改refCnt为1，发现oldRef为0，然后去进行回滚并抛出异常
     *          3.线程3调用obj.retain()，修改refCnt为2(在2进行了修改但是还没有回滚)，oldRef为1，线程3认为这是一个存活的对象，并不会抛出异常。
     *          4.线程1调用obj.deallocate()，释放了obj
     *      使用修改方案:
     *          1.线程1调用obj.release()，修改refCnt为奇数，实现里是修改为1，此时obj应该被进一步执行 deallocate
     *          2.线程2调用obj.retain()，修改refCnt为3，发现oldRef为1是奇数，直接抛出异常
     *          3.线程3调用obj.retain()，修改refCnt为5，发现oldRef为3是奇数，直接抛出异常
     *
     */
    private volatile int refCnt;

    protected AbstractReferenceCountedByteBuf(int maxCapacity) {
        super(maxCapacity);
        updater.setInitialValue(this);
    }

    // 2022/8/6 liang fix 判断是否是可用,实际上就是判断refCnt是否是奇数,但是这里还是有小优化,先尽量使用 == 来判断
    @Override
    boolean isAccessible() {
        // Try to do non-volatile read for performance as the ensureAccessible() is racy anyway and only provide
        // a best-effort guard.
        // 2022/8/7 liang fix 判断条件就是refCnt是否是奇数,如果是奇数说明已经被回收
        return updater.isLiveNonVolatile(this);
    }

    // 2022/8/6 liang fix 获取引用计数,当refCnt=奇数返回0,其他进行 >>> 处理
    @Override
    public int refCnt() {
        return updater.refCnt(this);
    }

    /**
     * An unsafe operation intended for use by a subclass that sets the reference count of the buffer directly
     */
    protected final void setRefCnt(int refCnt) {
        updater.setRefCnt(this, refCnt);
    }

    /**
     * An unsafe operation intended for use by a subclass that resets the reference count of the buffer to 1
     */
    protected final void resetRefCnt() {
        updater.resetRefCnt(this);
    }

    // 2022/8/7 liang fix 增加引用计数,注意这里使用的是getAndAdd 替换了 getAndSwap,速度更快
    @Override
    public ByteBuf retain() {
        return updater.retain(this);
    }

    @Override
    public ByteBuf retain(int increment) {
        return updater.retain(this, increment);
    }

    @Override
    public ByteBuf touch() {
        return this;
    }

    @Override
    public ByteBuf touch(Object hint) {
        return this;
    }

    @Override
    public boolean release() {
        return handleRelease(updater.release(this));
    }

    @Override
    public boolean release(int decrement) {
        return handleRelease(updater.release(this, decrement));
    }

    private boolean handleRelease(boolean result) {
        // 2022/8/18 liang fix 当实际引用<=0时,需要对对象进行回收,调用 deallocate() 方法
        if (result) {
            deallocate();
        }
        return result;
    }

    /**
     * Called once {@link #refCnt()} is equals 0.
     */
    // 2022/8/7 liang fix 回收对象,当对象的refCnt()方法返回0时,需要进行对象回收
    protected abstract void deallocate();
}
