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
package io.netty.channel;

import io.netty.util.concurrent.EventExecutorGroup;

/**
 * Special {@link EventExecutorGroup} which allows registering {@link Channel}s that get
 * processed for later selection during the event loop.
 * 特殊的 EventExecutorGroup，允许注册通道 Channel，以便在事件循环期间进行选择。
 *
 */
public interface EventLoopGroup extends EventExecutorGroup {
    /**
     * Return the next {@link EventLoop} to use
     */
    /**
     * 返回下一个 EventLoop
     * 复写了 EventExecutorGroup 的方法，改变了返回值类型
     */
    @Override
    EventLoop next();

    /**
     * Register a {@link Channel} with this {@link EventLoop}. The returned {@link ChannelFuture}
     * will get notified once the registration was complete.
     */
    /**
     * 向EventLoop注册一个Channel。
     * 一旦注册完成，返回的 ChannelFuture 将得到通知。
     */
    ChannelFuture register(Channel channel);

    /**
     * Register a {@link Channel} with this {@link EventLoop} using a {@link ChannelFuture}. The passed
     * {@link ChannelFuture} will get notified once the registration was complete and also will get returned.
     */
    /**
     * 使用参数 ChannelPromise 向 EventLoop 注册一个Channel。
     * 一旦注册完成，传递的 ChannelPromise 将得到通知，返回的 ChannelFuture 也将得到通知。
     */
    ChannelFuture register(ChannelPromise promise);

    /**
     * Register a {@link Channel} with this {@link EventLoop}. The passed {@link ChannelFuture}
     * will get notified once the registration was complete and also will get returned.
     *
     * @deprecated Use {@link #register(ChannelPromise)} instead.
     */
    /**
     * 向EventLoop注册一个Channel。
     * 一旦注册完成，传递的 ChannelPromise 将得到通知，返回的 ChannelFuture 也将得到通知。
     * @deprecated 推荐使用 register(ChannelPromise) 方法
     */
    @Deprecated
    ChannelFuture register(Channel channel, ChannelPromise promise);
}
