/*
 * Copyright 2012 The Netty Project
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
package io.netty.util.concurrent;

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThreadExecutorMap;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.jetbrains.annotations.Async.Schedule;

import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Abstract base class for {@link OrderedEventExecutor}'s that execute all its submitted tasks in a single thread.
 * liang fix 资料 : https://www.jianshu.com/p/f7cb63f0d1a1
 */
public abstract class SingleThreadEventExecutor extends AbstractScheduledEventExecutor implements OrderedEventExecutor {

    static final int DEFAULT_MAX_PENDING_EXECUTOR_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventexecutor.maxPendingTasks", Integer.MAX_VALUE));

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SingleThreadEventExecutor.class);


    private static final int ST_NOT_STARTED = 1;        //状态，，，未启动，未启动接受任务
    private static final int ST_STARTED = 2;                 //   已启动，运行
    private static final int ST_SHUTTING_DOWN = 3; //关闭中（平滑关闭）
    private static final int ST_SHUTDOWN = 4;       //已关闭
    private static final int ST_TERMINATED = 5;    // 终止

    private static final Runnable NOOP_TASK = new Runnable() {
        @Override
        public void run() {
            // Do nothing.
        }
    };

    private static final AtomicIntegerFieldUpdater<SingleThreadEventExecutor> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(SingleThreadEventExecutor.class, "state");

    private static final AtomicReferenceFieldUpdater<SingleThreadEventExecutor, ThreadProperties> PROPERTIES_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(
                    SingleThreadEventExecutor.class, ThreadProperties.class, "threadProperties");

    // 2022/7/18 liang fix 任务队列,不同于scheduledTaskQueue(定时任务队列)
    private final Queue<Runnable> taskQueue;

    private volatile Thread thread;

    @SuppressWarnings("unused")
    private volatile ThreadProperties threadProperties;

    private final Executor executor;

    // 2022/7/20 liang fix  线程中断状态
    private volatile boolean interrupted;

    // 2022/7/20 liang fix 信号锁
    private final CountDownLatch threadLock = new CountDownLatch(1);

    // 2022/7/20 liang fix netty自定义实现的钩子函数,当shutdown时调用,相比JVM的钩子函数,这里是保证了执行的顺序 & 线程安全
    private final Set<Runnable> shutdownHooks = new LinkedHashSet<Runnable>();
    // 2022/7/18 liang fix 如果设置为true, 当且仅当 添加              一个任务时，才唤醒选择器，比如是否唤醒 select() 函数。
    private final boolean addTaskWakesUp;

    private final int maxPendingTasks;
    private final RejectedExecutionHandler rejectedExecutionHandler;

    private long lastExecutionTime;

    @SuppressWarnings({ "FieldMayBeFinal", "unused" })
    // 2022/7/18 liang fix 当前状态
    private volatile int state = ST_NOT_STARTED;

    private volatile long gracefulShutdownQuietPeriod;
    private volatile long gracefulShutdownTimeout;
    private long gracefulShutdownStartTime;

    private final Promise<?> terminationFuture = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp);
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory,
            boolean addTaskWakesUp, int maxPendingTasks, RejectedExecutionHandler rejectedHandler) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp, maxPendingTasks, rejectedHandler);
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param executor          the {@link Executor} which will be used for executing
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor, boolean addTaskWakesUp) {
        this(parent, executor, addTaskWakesUp, DEFAULT_MAX_PENDING_EXECUTOR_TASKS, RejectedExecutionHandlers.reject());
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param executor          the {@link Executor} which will be used for executing
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */

    /**
     *liang fix
     *  创建一个新实例
     * @param parent            管理当前这个执行器的 EventExecutorGroup
     * @param executor          Executor 实例，用来执行 execute(Runnable command)
     * @param addTaskWakesUp    是否通过向任务队列 taskQueue 中添加唤醒任务 WAKEUP_TASK，来唤醒阻塞线程
     * @param taskQueue         自定义的任务队列
     * @param rejectedHandler   拒绝任务的处理器
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor,
                                        boolean addTaskWakesUp, int maxPendingTasks,
                                        RejectedExecutionHandler rejectedHandler) {
        super(parent);
        this.addTaskWakesUp = addTaskWakesUp;
        this.maxPendingTasks = Math.max(16, maxPendingTasks);
        // 2022/7/10 liang fix 包装者模式?保证返回的是一个 Executor, 线程执行器的包装类:ThreadExecutorMap ????
        this.executor = ThreadExecutorMap.apply(executor, this);
        // 2022/7/18 liang fix 这里的任务队列可由子类重新实现
        taskQueue = newTaskQueue(this.maxPendingTasks);
        rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor,
                                        boolean addTaskWakesUp, Queue<Runnable> taskQueue,
                                        RejectedExecutionHandler rejectedHandler) {
        super(parent);
        this.addTaskWakesUp = addTaskWakesUp;
        this.maxPendingTasks = DEFAULT_MAX_PENDING_EXECUTOR_TASKS;
        this.executor = ThreadExecutorMap.apply(executor, this);
        this.taskQueue = ObjectUtil.checkNotNull(taskQueue, "taskQueue");
        this.rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    /**
     * @deprecated Please use and override {@link #newTaskQueue(int)}.
     */
    @Deprecated
    protected Queue<Runnable> newTaskQueue() {
        return newTaskQueue(maxPendingTasks);
    }

    /**
     * Create a new {@link Queue} which will holds the tasks to execute. This default implementation will return a
     * {@link LinkedBlockingQueue} but if your sub-class of {@link SingleThreadEventExecutor} will not do any blocking
     * calls on the this {@link Queue} it may make sense to {@code @Override} this and return some more performant
     * implementation that does not support blocking operations at all.
     */
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return new LinkedBlockingQueue<Runnable>(maxPendingTasks);
    }

    /**
     * Interrupt the current running {@link Thread}.
     */
    protected void interruptThread() {
        Thread currentThread = thread;
        if (currentThread == null) {
            interrupted = true;
        } else {
            currentThread.interrupt();
        }
    }

    /**
     * liang fix
     *  检索并删除任务队列的头部，如果该任务队列为空则返回 null。
     *  这个方法不会有任何阻塞操作
     */
    protected Runnable pollTask() {
        assert inEventLoop();
        return pollTaskFrom(taskQueue);
    }

    // 2022/7/20 liang fix 循环获取任务,直到遇到 WAKEUP_TASK 任务
    protected static Runnable pollTaskFrom(Queue<Runnable> taskQueue) {
        for (;;) {
            Runnable task = taskQueue.poll();
            if (task != WAKEUP_TASK) {
                return task;
            }
        }
    }

    /**
     * Take the next {@link Runnable} from the task queue and so will block if no task is currently present.
     * <p>
     * Be aware that this method will throw an {@link UnsupportedOperationException} if the task queue, which was
     * created via {@link #newTaskQueue()}, does not implement {@link BlockingQueue}.
     * </p>
     *
     * @return {@code null} if the executor thread has been interrupted or waken up.
     */
    /**
     *   liang fix @date 2022/7/20
     *      从任务队列取task任务,这里的任务队列有两个,一个是定时任务,一个是普通任务
     *      定时任务优先级 > 普通任务
     *
     */
    protected Runnable takeTask() {
        // 2022/7/18 liang fix 断言当前调用线程是event loop的工作线程,自我保护之一
        assert inEventLoop();
        // liang fix 检查当前taskQueue, 如果不是BlockingQueue抛异常退出
        if (!(taskQueue instanceof BlockingQueue)) {
            throw new UnsupportedOperationException();
        }

        BlockingQueue<Runnable> taskQueue = (BlockingQueue<Runnable>) this.taskQueue;
        // 2022/7/20 liang fix 无限循环一直到 取到任务 或者执行线程被中断或唤醒
        for (;;) {
            // liang fix 从scheduledTaskQueue取一个scheduledTask, 注意方法是peek, 另外这个task有可能还没有到执行时间
            ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
            if (scheduledTask == null) {
                // liang fix scheduledTask为null, 说明当前scheduledTaskQueue中没有定时任务
                //  这个时候不用考虑定时任务, 直接从taskQueue中拿任务即可
                Runnable task = null;
                try {

                    /**
                     *   liang fix @date 2022/7/20
                     *       从taskQueue取任务, 如果没有任务会被阻塞, 直到取到任务,或者被Interrupted
                     *       可能有人有疑惑，如果在 taskQueue.take() 等待期间，有人添加了计划任务，那怎么办？
                     *       AbstractScheduledEventExecutor 的 schedule(final ScheduledFutureTask<V> task) 方法实现中，
                     *       如果不是执行器线程添加计划任务，它会直接调用execute(task), 将计划任务当成一个待执行任务添加到待执行任务队列中，唤醒线程。
                     *       在计划任务 ScheduledFutureTask 的run 方法中，会判断如果截止时间没有到，再把自己添加到计划任务队列中，而这时它一定是在执行器线程。
                     */
                    task = taskQueue.take();
                    // liang fix 检查如果是WAKEUP_TASK, 则需要跳过
                    if (task == WAKEUP_TASK) {
                        task = null;
                    }
                } catch (InterruptedException e) {
                    // Ignore
                }
                // liang fix 如果从taskQueue中拿任务成功,则返回, 如果没有任务或者没有成功,则返回的是null
                return task;
            } else {
                // liang fix scheduledTask不为null, 说明当前scheduledTaskQueue中有定时任务
                //  但是这个定时任务可能还没有到执行时间, 因此需要检查delayNanos
                long delayNanos = scheduledTask.delayNanos();
                Runnable task = null;
                if (delayNanos > 0) {
                    try {
                        // liang fix 从taskQueue取任务, 如果没有任务则最多等待delayNanos时间, 注意这里线程会被阻塞
                        //  如果delayNanos时间内有任务加入到taskQueue, 则可以实时取到这个任务
                        //  线程结束阻塞继续执行, 这个task就可以返回了
                        //  如果delayNanos时间内一直没有任务, 则timeout后线程结束阻塞,poll()方法返回null
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        // liang fix 还有一种可能就是delayNanos时间内被waken up
                        //  比如有新的scheduledTask加入, delay时间小于前面的delayNanos
                        //  因此不能等待delayNanos timeout,需要提前结束阻塞
                        //  Waken up.
                        return null;
                    }
                }
                if (task == null) {
                    // We need to fetch the scheduled tasks now as otherwise there may be a chance that
                    // scheduled tasks are never executed if there is always one task in the taskQueue.
                    // This is for example true for the read task of OIO Transport
                    // See https://github.com/netty/netty/issues/1614
                    // liang fix 继续尝试从scheduledTaskQueue取满足条件的task到taskQueue中
                    fetchFromScheduledTaskQueue();
                    // liang fix 然后再从taskQueue获取试试
                    task = taskQueue.poll();
                }

                if (task != null) {
                    // liang fix 只有task不为空时才返回并退出, 否则前面的for循环就一直
                    return task;
                }
            }
        }
    }

    // 2022/7/20 liang fix 将计划任务队列中当前过期的计划任务添加到 待执行任务队列 taskQueue
    private boolean fetchFromScheduledTaskQueue() {
        if (scheduledTaskQueue == null || scheduledTaskQueue.isEmpty()) {
            return true;
        }
        long nanoTime = getCurrentTimeNanos();
        for (;;) {
            //liang fix 从优先级队列中获取到执行期的元素
            Runnable scheduledTask = pollScheduledTask(nanoTime);
            if (scheduledTask == null) {
                return true;
            }
            //liang fix 把它加入到普通队列
            if (!taskQueue.offer(scheduledTask)) {
                // No space left in the task queue add it back to the scheduledTaskQueue so we pick it up again.
                scheduledTaskQueue.add((ScheduledFutureTask<?>) scheduledTask);
                return false;
            }
        }
    }

    /**
     * @return {@code true} if at least one scheduled task was executed.
     */
    private boolean executeExpiredScheduledTasks() {
        if (scheduledTaskQueue == null || scheduledTaskQueue.isEmpty()) {
            return false;
        }
        long nanoTime = getCurrentTimeNanos();
        Runnable scheduledTask = pollScheduledTask(nanoTime);
        if (scheduledTask == null) {
            return false;
        }
        do {
            safeExecute(scheduledTask);
        } while ((scheduledTask = pollScheduledTask(nanoTime)) != null);
        return true;
    }

    /**
     * @see Queue#peek()
     */
    protected Runnable peekTask() {
        assert inEventLoop();
        return taskQueue.peek();
    }

    /**
     * @see Queue#isEmpty()
     */
    protected boolean hasTasks() {
        assert inEventLoop();
        return !taskQueue.isEmpty();
    }

    /**
     * Return the number of tasks that are pending for processing.
     */
    public int pendingTasks() {
        return taskQueue.size();
    }

    /**
     * Add a task to the task queue, or throws a {@link RejectedExecutionException} if this instance was shutdown
     * before.
     */
    protected void addTask(Runnable task) {
        ObjectUtil.checkNotNull(task, "task");
        if (!offerTask(task)) {
            reject(task);
        }
    }

    final boolean offerTask(Runnable task) {
        if (isShutdown()) {
            reject();
        }
        return taskQueue.offer(task);
    }

    /**
     * @see Queue#remove(Object)
     */
    protected boolean removeTask(Runnable task) {
        return taskQueue.remove(ObjectUtil.checkNotNull(task, "task"));
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.
     *
     * @return {@code true} if and only if at least one task was run
     */
    protected boolean runAllTasks() {
        assert inEventLoop();
        boolean fetchedAll;
        boolean ranAtLeastOne = false;

        do {
            //liang fix 把优先级队列里面到期需要执行的任务，加入到普通队列
            fetchedAll = fetchFromScheduledTaskQueue();
            //liang fix 把普通队列全部执行
            if (runAllTasksFrom(taskQueue)) {
                ranAtLeastOne = true;
            }
        } while (!fetchedAll); // keep on processing until we fetched all scheduled tasks.

        //liang fix 记录最后一次任务结束时间
        if (ranAtLeastOne) {
            lastExecutionTime = getCurrentTimeNanos();
        }
        // 2022/7/20 liang fix 子类可以重写，相当于事件
        afterRunningAllTasks();
        return ranAtLeastOne;
    }

    /**
     * Execute all expired scheduled tasks and all current tasks in the executor queue until both queues are empty,
     * or {@code maxDrainAttempts} has been exceeded.
     * @param maxDrainAttempts The maximum amount of times this method attempts to drain from queues. This is to prevent
     *                         continuous task execution and scheduling from preventing the EventExecutor thread to
     *                         make progress and return to the selector mechanism to process inbound I/O events.
     * @return {@code true} if at least one task was run.
     */
    protected final boolean runScheduledAndExecutorTasks(final int maxDrainAttempts) {
        assert inEventLoop();
        boolean ranAtLeastOneTask;
        int drainAttempt = 0;
        do {
            // We must run the taskQueue tasks first, because the scheduled tasks from outside the EventLoop are queued
            // here because the taskQueue is thread safe and the scheduledTaskQueue is not thread safe.
            ranAtLeastOneTask = runExistingTasksFrom(taskQueue) | executeExpiredScheduledTasks();
        } while (ranAtLeastOneTask && ++drainAttempt < maxDrainAttempts);

        if (drainAttempt > 0) {
            lastExecutionTime = getCurrentTimeNanos();
        }
        afterRunningAllTasks();

        return drainAttempt > 0;
    }

    /**
     * Runs all tasks from the passed {@code taskQueue}.
     *
     * @param taskQueue To poll and execute all tasks.
     *
     * @return {@code true} if at least one task was executed.
     */
    protected final boolean runAllTasksFrom(Queue<Runnable> taskQueue) {
        Runnable task = pollTaskFrom(taskQueue);
        if (task == null) {
            return false;
        }
        for (;;) {
            safeExecute(task);
            task = pollTaskFrom(taskQueue);
            if (task == null) {
                return true;
            }
        }
    }

    /**
     * What ever tasks are present in {@code taskQueue} when this method is invoked will be {@link Runnable#run()}.
     * @param taskQueue the task queue to drain.
     * @return {@code true} if at least {@link Runnable#run()} was called.
     */
    private boolean runExistingTasksFrom(Queue<Runnable> taskQueue) {
        Runnable task = pollTaskFrom(taskQueue);
        if (task == null) {
            return false;
        }
        int remaining = Math.min(maxPendingTasks, taskQueue.size());
        safeExecute(task);
        // Use taskQueue.poll() directly rather than pollTaskFrom() since the latter may
        // silently consume more than one item from the queue (skips over WAKEUP_TASK instances)
        while (remaining-- > 0 && (task = taskQueue.poll()) != null) {
            safeExecute(task);
        }
        return true;
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.  This method stops running
     * the tasks in the task queue and returns if it ran longer than {@code timeoutNanos}.
     */
    protected boolean runAllTasks(long timeoutNanos) {
        //liang fix 把优先级队列中到期的任务抓取到普通队列
        fetchFromScheduledTaskQueue();
        //liang fix 获取普通多列当中任务
        Runnable task = pollTask();
        if (task == null) {
            //liang fix 事件
            afterRunningAllTasks();
            return false;
        }
        //liang fix 设置允许执行的时间
        final long deadline = timeoutNanos > 0 ? getCurrentTimeNanos() + timeoutNanos : 0;
        long runTasks = 0;
        long lastExecutionTime;
        for (;;) {
            //liang fix 执行任务,实际就是使用了try catch 来包裹任务执行,调用的还是 task.run()方法
            safeExecute(task);

            runTasks ++;

            // Check timeout every 64 tasks because nanoTime() is relatively expensive.
            // XXX: Hard-coded value - will make it configurable if it is really a problem.
            // 2022/7/20 liang fix 每64个任务检查一下时间
            if ((runTasks & 0x3F) == 0) {
                // 2022/7/20 liang fix 系统当前时间如果超过了允许执行的时间，则结束循环，让EventLoop线程去干其他事情
                lastExecutionTime = getCurrentTimeNanos();
                if (lastExecutionTime >= deadline) {
                    break;
                }
            }

            //liang fix 获取任务
            task = pollTask();
            if (task == null) {
                //liang fix 如果task为空，记录最后一个任务的执行时间
                lastExecutionTime = getCurrentTimeNanos();
                break;
            }
        }

        afterRunningAllTasks();
        this.lastExecutionTime = lastExecutionTime;
        return true;
    }

    /**
     * Invoked before returning from {@link #runAllTasks()} and {@link #runAllTasks(long)}.
     */
    // 2022/7/20 liang fix 在 runAllTasks() 和 runAllTasks(long) 方法返回之前调用。
    @UnstableApi
    protected void afterRunningAllTasks() { }

    /**
     * Returns the amount of time left until the scheduled task with the closest dead line is executed.
     */
    protected long delayNanos(long currentTimeNanos) {
        currentTimeNanos -= initialNanoTime();

        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return SCHEDULE_PURGE_INTERVAL;
        }

        return scheduledTask.delayNanos(currentTimeNanos);
    }

    /**
     * Returns the absolute point in time (relative to {@link #getCurrentTimeNanos()}) at which the next
     * closest scheduled task should run.
     */
    @UnstableApi
    protected long deadlineNanos() {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return getCurrentTimeNanos() + SCHEDULE_PURGE_INTERVAL;
        }
        return scheduledTask.deadlineNanos();
    }

    /**
     * Updates the internal timestamp that tells when a submitted task was executed most recently.
     * {@link #runAllTasks()} and {@link #runAllTasks(long)} updates this timestamp automatically, and thus there's
     * usually no need to call this method.  However, if you take the tasks manually using {@link #takeTask()} or
     * {@link #pollTask()}, you have to call this method at the end of task execution loop for accurate quiet period
     * checks.
     */
    protected void updateLastExecutionTime() {
        lastExecutionTime = getCurrentTimeNanos();
    }

    /**
     * Run the tasks in the {@link #taskQueue}
     */
    protected abstract void run();

    /**
     * Do nothing, sub-classes may override
     */
    protected void cleanup() {
        // NOOP
    }

    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop) {
            // Use offer as we actually only need this to unblock the thread and if offer fails we do not care as there
            // is already something in the queue.
            taskQueue.offer(WAKEUP_TASK);
        }
    }

    @Override
    public boolean inEventLoop(Thread thread) {
        return thread == this.thread;
    }

    /**
     * Add a {@link Runnable} which will be executed on shutdown of this instance
     */
    public void addShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.add(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.add(task);
                }
            });
        }
    }

    /**
     * Remove a previous added {@link Runnable} as a shutdown hook
     */
    public void removeShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.remove(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.remove(task);
                }
            });
        }
    }

    private boolean runShutdownHooks() {
        boolean ran = false;
        // Note shutdown hooks can add / remove shutdown hooks.
        while (!shutdownHooks.isEmpty()) {
            List<Runnable> copy = new ArrayList<Runnable>(shutdownHooks);
            shutdownHooks.clear();
            for (Runnable task: copy) {
                try {
                    runTask(task);
                } catch (Throwable t) {
                    logger.warn("Shutdown hook raised an exception.", t);
                } finally {
                    ran = true;
                }
            }
        }

        if (ran) {
            lastExecutionTime = getCurrentTimeNanos();
        }

        return ran;
    }

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        ObjectUtil.checkPositiveOrZero(quietPeriod, "quietPeriod");
        if (timeout < quietPeriod) {
            throw new IllegalArgumentException(
                    "timeout: " + timeout + " (expected >= quietPeriod (" + quietPeriod + "))");
        }
        ObjectUtil.checkNotNull(unit, "unit");

        if (isShuttingDown()) {
            return terminationFuture();
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            if (isShuttingDown()) {
                return terminationFuture();
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTTING_DOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                        newState = ST_SHUTTING_DOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }
        gracefulShutdownQuietPeriod = unit.toNanos(quietPeriod);
        gracefulShutdownTimeout = unit.toNanos(timeout);

        if (ensureThreadStarted(oldState)) {
            return terminationFuture;
        }

        if (wakeup) {
            taskQueue.offer(WAKEUP_TASK);
            if (!addTaskWakesUp) {
                wakeup(inEventLoop);
            }
        }

        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        if (isShutdown()) {
            return;
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            if (isShuttingDown()) {
                return;
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTDOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                    case ST_SHUTTING_DOWN:
                        newState = ST_SHUTDOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }

        if (ensureThreadStarted(oldState)) {
            return;
        }

        if (wakeup) {
            taskQueue.offer(WAKEUP_TASK);
            if (!addTaskWakesUp) {
                wakeup(inEventLoop);
            }
        }
    }

    @Override
    public boolean isShuttingDown() {
        return state >= ST_SHUTTING_DOWN;
    }

    @Override
    public boolean isShutdown() {
        return state >= ST_SHUTDOWN;
    }

    @Override
    public boolean isTerminated() {
        return state == ST_TERMINATED;
    }

    /**
     * Confirm that the shutdown if the instance should be done now!
     */
    protected boolean confirmShutdown() {
        if (!isShuttingDown()) {
            return false;
        }

        if (!inEventLoop()) {
            throw new IllegalStateException("must be invoked from an event loop");
        }

        //liang fix 把优先级队列全部清空
        cancelScheduledTasks();

        if (gracefulShutdownStartTime == 0) {
            gracefulShutdownStartTime = getCurrentTimeNanos();
        }

        //liang fix 把任务全部执行完毕
        if (runAllTasks() || runShutdownHooks()) {
            if (isShutdown()) {
                // Executor shut down - no new tasks anymore.
                return true;
            }

            // There were tasks in the queue. Wait a little bit more until no tasks are queued for the quiet period or
            // terminate if the quiet period is 0.
            // See https://github.com/netty/netty/issues/4241
            if (gracefulShutdownQuietPeriod == 0) {
                return true;
            }
            taskQueue.offer(WAKEUP_TASK);
            return false;
        }

        final long nanoTime = getCurrentTimeNanos();

        if (isShutdown() || nanoTime - gracefulShutdownStartTime > gracefulShutdownTimeout) {
            return true;
        }

        if (nanoTime - lastExecutionTime <= gracefulShutdownQuietPeriod) {
            // Check if any tasks were added to the queue every 100ms.
            // TODO: Change the behavior of takeTask() so that it returns on timeout.
            taskQueue.offer(WAKEUP_TASK);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }

            return false;
        }

        // No tasks were added for last quiet period - hopefully safe to shut down.
        // (Hopefully because we really cannot make a guarantee that there will be no execute() calls by a user.)
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        ObjectUtil.checkNotNull(unit, "unit");
        if (inEventLoop()) {
            throw new IllegalStateException("cannot await termination of the current thread");
        }

        threadLock.await(timeout, unit);

        return isTerminated();
    }

    @Override
    public void execute(Runnable task) {
        execute0(task);
    }

    @Override
    public void lazyExecute(Runnable task) {
        lazyExecute0(task);
    }

    private void execute0(@Schedule Runnable task) {
        ObjectUtil.checkNotNull(task, "task");
        execute(task, !(task instanceof LazyRunnable) && wakesUpForTask(task));
    }

    private void lazyExecute0(@Schedule Runnable task) {
        execute(ObjectUtil.checkNotNull(task, "task"), false);
    }

    private void execute(Runnable task, boolean immediate) {
        // 2022/7/18 liang fix 判断是否是在当前线程中提交
        boolean inEventLoop = inEventLoop();
        //liang fix 把任务添加到队列,注意这里是可以被不同线程调用的，所以有并发冲突问题。因此任务队列taskQueue 必须是一个线程安全的队列，就是可以处理并发问题。
        addTask(task);
        if (!inEventLoop) {
            //liang fix 尝试启动工作线程，启动成功的话就会死循环不断地执行任务
            startThread();
            //liang fix 如果startThread方法返回，一种情况是已经启动，或者是关闭状态
            if (isShutdown()) {
                //liang fix 已经关闭
                boolean reject = false;
                try {
                    //liang fix 删除这个任务
                    if (removeTask(task)) {
                        reject = true;
                    }
                } catch (UnsupportedOperationException e) {
                    // The task queue does not support removal so the best thing we can do is to just move on and
                    // hope we will be able to pick-up the task before its completely terminated.
                    // In worst case we will log on termination.
                }
                //liang fix 如果删除成功，抛出拒绝异常
                if (reject) {
                    reject();
                }
            }
        }

        if (!addTaskWakesUp && immediate) {
            wakeup(inEventLoop);
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks, timeout, unit);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks, timeout, unit);
    }

    private void throwIfInEventLoop(String method) {
        if (inEventLoop()) {
            throw new RejectedExecutionException("Calling " + method + " from within the EventLoop is not allowed");
        }
    }

    /**
     * Returns the {@link ThreadProperties} of the {@link Thread} that powers the {@link SingleThreadEventExecutor}.
     * If the {@link SingleThreadEventExecutor} is not started yet, this operation will start it and block until
     * it is fully started.
     */
    public final ThreadProperties threadProperties() {
        ThreadProperties threadProperties = this.threadProperties;
        if (threadProperties == null) {
            Thread thread = this.thread;
            if (thread == null) {
                assert !inEventLoop();
                submit(NOOP_TASK).syncUninterruptibly();
                thread = this.thread;
                assert thread != null;
            }

            threadProperties = new DefaultThreadProperties(thread);
            if (!PROPERTIES_UPDATER.compareAndSet(this, null, threadProperties)) {
                threadProperties = this.threadProperties;
            }
        }

        return threadProperties;
    }

    /**
     * @deprecated use {@link AbstractEventExecutor.LazyRunnable}
     */
    @Deprecated
    protected interface NonWakeupRunnable extends LazyRunnable { }

    /**
     * Can be overridden to control which tasks require waking the {@link EventExecutor} thread
     * if it is waiting so that they can be run immediately.
     */
    protected boolean wakesUpForTask(Runnable task) {
        return true;
    }

    protected static void reject() {
        throw new RejectedExecutionException("event executor terminated");
    }

    /**
     * Offers the task to the associated {@link RejectedExecutionHandler}.
     *
     * @param task to reject.
     */
    protected final void reject(Runnable task) {
        rejectedExecutionHandler.rejected(task, this);
    }

    // ScheduledExecutorService implementation

    private static final long SCHEDULE_PURGE_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    private void startThread() {
        //liang fix 必须是未启动状态,如果关闭中，或者关闭了都不会再次启动,只有执行器处于未运行状态，才需要开启运行
        if (state == ST_NOT_STARTED) {
            //liang fix 把状态由ST_NOT_STARTED设置为ST_STARTED，原子方式更新，只会有一个线程操作成功
            if (STATE_UPDATER.compareAndSet(this, ST_NOT_STARTED, ST_STARTED)) {
                boolean success = false;
                try {
                    //liang fix 启动线程
                    doStartThread();
                    success = true;
                } finally {
                    //liang fix 如果启动失败，则把状态还原成ST_NOT_STARTED
                    if (!success) {
                        STATE_UPDATER.compareAndSet(this, ST_STARTED, ST_NOT_STARTED);
                    }
                }
            }
        }
    }

    private boolean ensureThreadStarted(int oldState) {
        if (oldState == ST_NOT_STARTED) {
            try {
                doStartThread();
            } catch (Throwable cause) {
                STATE_UPDATER.set(this, ST_TERMINATED);
                terminationFuture.tryFailure(cause);

                if (!(cause instanceof Exception)) {
                    // Also rethrow as it may be an OOME for example
                    PlatformDependent.throwException(cause);
                }
                return true;
            }
        }
        return false;
    }

    private void doStartThread() {
        assert thread == null;
        //liang fix 启动的动作封装成一个任务，executor会启动一个新的线程去执行
        // 这里直接调用 executor.execute 方法，
        // 我们要确保该方法每次都能提供新线程，
        // 否则多个执行器绑定的线程是同一个，是会有问题。
        // 因为一般 SingleThreadEventExecutor.this.run() 方法都是死循环，
        // 这就导致它会占用线程，导致共享线程的其他执行器，是没有办法执行任务的。
        executor.execute(new Runnable() {
            @Override
            public void run() {
                //liang fix 获取当前线程，赋值给 thread，就是执行器线程
                thread = Thread.currentThread();
                if (interrupted) {
                    thread.interrupt();
                }

                boolean success = false;
                updateLastExecutionTime();
                try {
                    //liang fix 需要子类实现具体逻辑 ,一般情况下，这个方法里面利用死循环，来获取待执行任务队列 taskQueue 中的任务并运行。
                    SingleThreadEventExecutor.this.run();
                    success = true;
                } catch (Throwable t) {
                    logger.warn("Unexpected exception from an event executor: ", t);
                } finally {
                    // liang fix 采用死循环，使用 CAS 方式，确保执行器状态，变成 ST_SHUTTING_DOWN，或者已经大于 ST_SHUTTING_DOWN
                    for (;;) {
                        int oldState = state;
                        //liang fix 如果run方法结束，才会执行这里，等待状态变为》=ST_SHUTTING_DOWN时结束方法
                        if (oldState >= ST_SHUTTING_DOWN || STATE_UPDATER.compareAndSet(
                                SingleThreadEventExecutor.this, oldState, ST_SHUTTING_DOWN)) {
                            break;
                        }
                    }

                    // Check if confirmShutdown() was called at the end of the loop.
                    // liang fix 检查是否在循环结束时调用了confirmShutdown() 方法。
                    if (success && gracefulShutdownStartTime == 0) {
                        if (logger.isErrorEnabled()) {
                            logger.error("Buggy " + EventExecutor.class.getSimpleName() + " implementation; " +
                                    SingleThreadEventExecutor.class.getSimpleName() + ".confirmShutdown() must " +
                                    "be called before run() implementation terminates.");
                        }
                    }

                    try {
                        // Run all remaining tasks and shutdown hooks. At this point the event loop
                        // is in ST_SHUTTING_DOWN state still accepting tasks which is needed for
                        // graceful shutdown with quietPeriod.
                        // liang fix
                        //  通过死循环和confirmShutdown() 方法，确保运行所有剩余的任务和关闭钩子函数。
                        //  此时，事件循环执行器还处于开始shutdown ST_SHUTTING_DOWN 状态，
                        //  如果使用 shutdownGracefully 方法优雅关闭，仍然可以在安静期 quietPeriod 内接受任务。
                        for (;;) {
                            //liang fix 等待所有任务执行完毕，所有结束钩子任务执行完毕
                            if (confirmShutdown()) {
                                break;
                            }
                        }

                        // Now we want to make sure no more tasks can be added from this point. This is
                        // achieved by switching the state. Any new tasks beyond this point will be rejected.
                        //liang fix 设置为结束状态
                        // 现在我们要确保不再从这里添加任务
                        // 这是通过切换状态来实现的
                        // 超过此点的任何新任务都将被拒绝
                        for (;;) {
                            int oldState = state;
                            if (oldState >= ST_SHUTDOWN || STATE_UPDATER.compareAndSet(
                                    SingleThreadEventExecutor.this, oldState, ST_SHUTDOWN)) {
                                break;
                            }
                        }

                        // We have the final set of tasks in the queue now, no more can be added, run all remaining.
                        // No need to loop here, this is the final pass.
                        // liang fix 现在队列中只剩下最后一组任务，不会再添加了，运行剩下的所有任务。 这里不需要循环，这是最后一次。
                        confirmShutdown();
                    } finally {
                        try {
                            //liang fix 释放资源
                            cleanup();
                        } finally {
                            // Lets remove all FastThreadLocals for the Thread as we are about to terminate and notify
                            // the future. The user may block on the future and once it unblocks the JVM may terminate
                            // and start unloading classes.
                            // See https://github.com/netty/netty/issues/6596.
                            FastThreadLocal.removeAll();

                            STATE_UPDATER.set(SingleThreadEventExecutor.this, ST_TERMINATED);
                            threadLock.countDown();
                            int numUserTasks = drainTasks();
                            if (numUserTasks > 0 && logger.isWarnEnabled()) {
                                logger.warn("An event executor terminated with " +
                                        "non-empty task queue (" + numUserTasks + ')');
                            }
                            terminationFuture.setSuccess(null);
                        }
                    }
                }
            }
        });
    }

    final int drainTasks() {
        int numTasks = 0;
        for (;;) {
            Runnable runnable = taskQueue.poll();
            if (runnable == null) {
                break;
            }
            // WAKEUP_TASK should be just discarded as these are added internally.
            // The important bit is that we not have any user tasks left.
            if (WAKEUP_TASK != runnable) {
                numTasks++;
            }
        }
        return numTasks;
    }

    private static final class DefaultThreadProperties implements ThreadProperties {
        private final Thread t;

        DefaultThreadProperties(Thread t) {
            this.t = t;
        }

        @Override
        public State state() {
            return t.getState();
        }

        @Override
        public int priority() {
            return t.getPriority();
        }

        @Override
        public boolean isInterrupted() {
            return t.isInterrupted();
        }

        @Override
        public boolean isDaemon() {
            return t.isDaemon();
        }

        @Override
        public String name() {
            return t.getName();
        }

        @Override
        public long id() {
            return t.getId();
        }

        @Override
        public StackTraceElement[] stackTrace() {
            return t.getStackTrace();
        }

        @Override
        public boolean isAlive() {
            return t.isAlive();
        }
    }
}
