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
package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.jctools.queues.MessagePassingQueue;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static io.netty.util.internal.PlatformDependent.newMpscQueue;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 */
/**
 * liang fix @date 2022/8/6
 *  虽然是一个抽象类,但是只有一个没有实现的方法,那就是
 *      {@link Recycler#newObject(Handle)}
 */
public abstract class Recycler<T> {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);
    private static final Handle<?> NOOP_HANDLE = new Handle<Object>() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }

        @Override
        public String toString() {
            return "NOOP_HANDLE";
        }
    };

    //2019-12-19 liang fix stack 默认最大容量
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    private static final int RATIO;
    private static final int DEFAULT_QUEUE_CHUNK_SIZE_PER_THREAD;
    private static final boolean BLOCKING_POOL;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;
        DEFAULT_QUEUE_CHUNK_SIZE_PER_THREAD = SystemPropertyUtil.getInt("io.netty.recycler.chunkSize", 32);

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        BLOCKING_POOL = SystemPropertyUtil.getBoolean("io.netty.recycler.blocking", false);

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
                logger.debug("-Dio.netty.recycler.chunkSize: disabled");
                logger.debug("-Dio.netty.recycler.blocking: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
                logger.debug("-Dio.netty.recycler.chunkSize: {}", DEFAULT_QUEUE_CHUNK_SIZE_PER_THREAD);
                logger.debug("-Dio.netty.recycler.blocking: {}", BLOCKING_POOL);
            }
        }
    }

    private final int maxCapacityPerThread;
    private final int interval;
    private final int chunkSize;
    private final FastThreadLocal<LocalPool<T>> threadLocal = new FastThreadLocal<LocalPool<T>>() {
        @Override
        protected LocalPool<T> initialValue() {
            // 2022/10/18 liang fix 构建一个LocolPool,
            /**
             * liang fix @date 2022/10/18
             *      maxCapacityPerThread 最大容量 4096
             *      interval 回收阈值 8 , 每间隔 8 回收一个对象
             *      chunkSize MPSC中每个chunk的大小 默认32
             */
            return new LocalPool<T>(maxCapacityPerThread, interval, chunkSize);
        }

        // 2022/10/18 liang fix 线程执行完需要对对象池进行 clear
        @Override
        protected void onRemoval(LocalPool<T> value) throws Exception {
            super.onRemoval(value);
            MessagePassingQueue<DefaultHandle<T>> handles = value.pooledHandles;
            value.pooledHandles = null;
            handles.clear();
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, RATIO, DEFAULT_QUEUE_CHUNK_SIZE_PER_THREAD);
    }

    /**
     * @deprecated Use one of the following instead:
     * {@link #Recycler()}, {@link #Recycler(int)}, {@link #Recycler(int, int, int)}.
     */
    @Deprecated
    @SuppressWarnings("unused") // Parameters we can't remove due to compatibility.
    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, RATIO, DEFAULT_QUEUE_CHUNK_SIZE_PER_THREAD);
    }

    /**
     * @deprecated Use one of the following instead:
     * {@link #Recycler()}, {@link #Recycler(int)}, {@link #Recycler(int, int, int)}.
     */
    @Deprecated
    @SuppressWarnings("unused") // Parameters we can't remove due to compatibility.
    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        this(maxCapacityPerThread, ratio, DEFAULT_QUEUE_CHUNK_SIZE_PER_THREAD);
    }

    /**
     * @deprecated Use one of the following instead:
     * {@link #Recycler()}, {@link #Recycler(int)}, {@link #Recycler(int, int, int)}.
     */
    @Deprecated
    @SuppressWarnings("unused") // Parameters we can't remove due to compatibility.
    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread, int delayedQueueRatio) {
        this(maxCapacityPerThread, ratio, DEFAULT_QUEUE_CHUNK_SIZE_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int ratio, int chunkSize) {
        interval = max(0, ratio);
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.chunkSize = 0;
        } else {
            this.maxCapacityPerThread = max(4, maxCapacityPerThread);
            this.chunkSize = max(2, min(chunkSize, this.maxCapacityPerThread >> 1));
        }
    }

    @SuppressWarnings("unchecked")
    public final T get() {
        // 2022/8/6 liang fix 最大容量标识,为0说明不需要对象的回收,不用对创建的对象构造一个钩子, 默认情况下maxCapacityPerThread= 4096
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }

        // 2022/10/18 liang fix 新的对象池技术,使用LocalPool替代了 WeakOrderQueue
        LocalPool<T> localPool = threadLocal.get();

        // 2022/10/18 liang fix 尝试从 MPSC 队列获取一个 claim 状态的 handle,有可能返回null
        DefaultHandle<T> handle = localPool.claim();

        T obj;
        // 2022/10/18 liang fix 没有获取到handle,说明没有可复用的对象
        if (handle == null) {
            //liang fix 构建一个handle,这里会使用到 ratioInterval(默认是8), 用来标识当前对象是否需要回收
            //  如果需要回收,那么handle 就是 DefaultHandle 对象
            handle = localPool.newHandle();
            if (handle != null) {
                // 2022/10/17 liang fix 创建对象,注意创建的对象里必须有一一个handle, 这个handle在回收时有用
                obj = newObject(handle);
                // 2022/10/18 liang fix 互相引用
                handle.set(obj);
            } else {
                // 2022/10/18 liang fix 不需要回收的对象
                obj = newObject((Handle<T>) NOOP_HANDLE);
            }
        } else {
            // 2022/10/18 liang fix 有可复用的对象
            obj = handle.get();
        }

        return obj;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        handle.recycle(o);
        return true;
    }

    final int threadLocalSize() {
        return threadLocal.get().pooledHandles.size();
    }

    protected abstract T newObject(Handle<T> handle);

    @SuppressWarnings("ClassNameSameAsAncestorName") // Can't change this due to compatibility.
    public interface Handle<T> extends ObjectPool.Handle<T>  { }

    private static final class DefaultHandle<T> implements Handle<T> {
        // 2022/10/18 liang fix 清理结束,默认初始化的状态
        private static final int STATE_CLAIMED = 0;
        // 2022/10/18 liang fix 可用状态,当添加到缓存中的状态
        private static final int STATE_AVAILABLE = 1;

        private static final AtomicIntegerFieldUpdater<DefaultHandle<?>> STATE_UPDATER;
        static {
            AtomicIntegerFieldUpdater<?> updater = AtomicIntegerFieldUpdater.newUpdater(DefaultHandle.class, "state");
            //noinspection unchecked
            STATE_UPDATER = (AtomicIntegerFieldUpdater<DefaultHandle<?>>) updater;
        }

        @SuppressWarnings({"FieldMayBeFinal", "unused"}) // Updated by STATE_UPDATER.
        // 2022/10/18 liang fix 当前handle的状态,默认为0
        private volatile int state; // State is initialised to STATE_CLAIMED (aka. 0) so they can be released.
        private final LocalPool<T> localPool;
        private T value;

        DefaultHandle(LocalPool<T> localPool) {
            this.localPool = localPool;
        }

        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }
            localPool.release(this);
        }

        T get() {
            return value;
        }

        void set(T value) {
            this.value = value;
        }

        boolean availableToClaim() {
            if (state != STATE_AVAILABLE) {
                return false;
            }
            // 2022/10/18 liang fix 修改状态
            return STATE_UPDATER.compareAndSet(this, STATE_AVAILABLE, STATE_CLAIMED);
        }

        void toAvailable() {
            int prev = STATE_UPDATER.getAndSet(this, STATE_AVAILABLE);
            if (prev == STATE_AVAILABLE) {
                throw new IllegalStateException("Object has been recycled already.");
            }
        }
    }

    // 2022/10/18 liang fix 重写的线程池实现,不在依赖 WeakOrderQueue 的弱引用实现
    private static final class LocalPool<T> {
        // 2022/10/18 liang fix 阈值, 用来限制对象的回收(不是每次都进行对象的回收) 默认 8
        private final int ratioInterval;
        private volatile MessagePassingQueue<DefaultHandle<T>> pooledHandles;

        // 2022/10/18 liang fix 阈值计数器
        private int ratioCounter;

        @SuppressWarnings("unchecked")
        LocalPool(int maxCapacity, int ratioInterval, int chunkSize) {
            this.ratioInterval = ratioInterval;
            if (BLOCKING_POOL) {
                pooledHandles = new BlockingMessageQueue<DefaultHandle<T>>(maxCapacity);
            } else {
                // 2022/10/18 liang fix 默认是 MPSC 的一个queue
                pooledHandles = (MessagePassingQueue<DefaultHandle<T>>) newMpscQueue(chunkSize, maxCapacity);
            }
            ratioCounter = ratioInterval; // Start at interval so the first one will be recycled.
        }

        DefaultHandle<T> claim() {
            MessagePassingQueue<DefaultHandle<T>> handles = pooledHandles;
            if (handles == null) {
                return null;
            }
            DefaultHandle<T> handle;
            // 2022/10/18 liang fix 这里会循环找到一个可用的handle(同时需要修改这个状态为可用,如果修改失败说明以及被其他线程修改,需要重新获取handle)或者返回null
            do {
                // 2022/10/18 liang fix 这里使用的是 relaxedPoll() 方法,这个方法的意思是如果有 生产者正在填充但元素尚不可见,这种情况也会返回null
                handle = handles.relaxedPoll();
            } while (handle != null && !handle.availableToClaim());

            // 2022/10/18 liang fix null 或者一个可用的handle
            return handle;
        }

        void release(DefaultHandle<T> handle) {
            MessagePassingQueue<DefaultHandle<T>> handles = pooledHandles;
            handle.toAvailable();
            if (handles != null) {
                // 2022/10/18 liang fix 添加任务到 handles 队列中
                handles.relaxedOffer(handle);
            }
        }

        DefaultHandle<T> newHandle() {
            if (++ratioCounter >= ratioInterval) {
                ratioCounter = 0;
                return new DefaultHandle<T>(this);
            }
            return null;
        }
    }

    /**
     * This is an implementation of {@link MessagePassingQueue}, similar to what might be returned from
     * {@link PlatformDependent#newMpscQueue(int)}, but intended to be used for debugging purpose.
     * The implementation relies on synchronised monitor locks for thread-safety.
     * The {@code drain} and {@code fill} bulk operations are not supported by this implementation.
     */
    private static final class BlockingMessageQueue<T> implements MessagePassingQueue<T> {
        private final Queue<T> deque;
        private final int maxCapacity;

        BlockingMessageQueue(int maxCapacity) {
            this.maxCapacity = maxCapacity;
            // This message passing queue is backed by an ArrayDeque instance,
            // made thread-safe by synchronising on `this` BlockingMessageQueue instance.
            // Why ArrayDeque?
            // We use ArrayDeque instead of LinkedList or LinkedBlockingQueue because it's more space efficient.
            // We use ArrayDeque instead of ArrayList because we need the queue APIs.
            // We use ArrayDeque instead of ConcurrentLinkedQueue because CLQ is unbounded and has O(n) size().
            // We use ArrayDeque instead of ArrayBlockingQueue because ABQ allocates its max capacity up-front,
            // and these queues will usually have large capacities, in potentially great numbers (one per thread),
            // but often only have comparatively few items in them.
            deque = new ArrayDeque<T>();
        }

        @Override
        public synchronized boolean offer(T e) {
            if (deque.size() == maxCapacity) {
                return false;
            }
            return deque.offer(e);
        }

        @Override
        public synchronized T poll() {
            return deque.poll();
        }

        @Override
        public synchronized T peek() {
            return deque.peek();
        }

        @Override
        public synchronized int size() {
            return deque.size();
        }

        @Override
        public synchronized void clear() {
            deque.clear();
        }

        @Override
        public synchronized boolean isEmpty() {
            return deque.isEmpty();
        }

        @Override
        public int capacity() {
            return maxCapacity;
        }

        @Override
        public boolean relaxedOffer(T e) {
            return offer(e);
        }

        @Override
        public T relaxedPoll() {
            return poll();
        }

        @Override
        public T relaxedPeek() {
            return peek();
        }

        @Override
        public int drain(Consumer<T> c, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int fill(Supplier<T> s, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int drain(Consumer<T> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int fill(Supplier<T> s) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void drain(Consumer<T> c, WaitStrategy wait, ExitCondition exit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void fill(Supplier<T> s, WaitStrategy wait, ExitCondition exit) {
            throw new UnsupportedOperationException();
        }
    }
}
