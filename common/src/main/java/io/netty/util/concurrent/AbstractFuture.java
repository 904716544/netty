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
package io.netty.util.concurrent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Abstract {@link Future} implementation which does not allow for cancellation.
 *  liang fix  java.util.concurrent.FutureTask中的实现方式十分相似。
 * @param <V>
 */
public abstract class AbstractFuture<V> implements Future<V> {

    /**
     *liang fix @date 2022/12/11 9:13 下午
     * @author liliang
     * liang fix 永久阻塞等待获取結果的方法
     */
    @Override
    public V get() throws InterruptedException, ExecutionException {
        // 2022/7/13 liang fix  阻塞直到异步操作完成
        await();
        // 2022/7/13 liang fix 從永久阻塞中唤醒后，先判断Future是否执行异常
        Throwable cause = cause();
        if (cause == null) {
            // 2022/7/13 liang fix 异常为空說明执行成功，调用getNow()方法返回结果
            return getNow();
        }
        // 2022/7/13 liang fix 异常為空不為空，這裏區分特定的取消异常則轉換為CancellationException拋出
        if (cause instanceof CancellationException) {
            // 2022/7/13 liang fix 由用戶取消
            throw (CancellationException) cause;
        }
        // 2022/7/13 liang fix 非取消异常的其他所有异常都被包裝為執行异常ExecutionException拋出
        throw new ExecutionException(cause);
    }

    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        // 2022/7/13 liang fix 調用響應中斷的帶超時時限等待方法進行阻塞
        if (await(timeout, unit)) {
            Throwable cause = cause();
            if (cause == null) {
                return getNow();
            }
            if (cause instanceof CancellationException) {
                throw (CancellationException) cause;
            }
            throw new ExecutionException(cause);
        }
        // 2022/7/13 liang fix 方法步入此處說明等待超時，則拋出超時异常TimeoutException
        throw new TimeoutException();
    }
}
