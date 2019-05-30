/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.anlystar.common.httprpc.callback;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 封装异步请求的结果
 *
 * @param <T>
 */
public class CallbackFuture<T> implements Future<T>, Callback<T> {

    // 取消标志
    private static char CANCEL_YES = '1';
    // 未取消标志
    private static char CANCEL_NO = '0';

    private final CountDownLatch latch = new CountDownLatch(1);

    private T result = null;

    private Throwable error = null;

    private char canceled = CANCEL_NO;

    @Override
    public void handleResult(T result) {
        this.result = result;
        latch.countDown();
    }

    @Override
    public void handleError(Throwable error) {
        this.error = error;
        latch.countDown();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        canceled = CANCEL_YES;
        latch.countDown();
        return false;
    }

    @Override
    public boolean isCancelled() {
        return canceled == CANCEL_YES;
    }

    @Override
    public boolean isDone() {
        return latch.getCount() <= 0;
    }

    // 暂未考虑 cancel 情况
    @Override
    public T get() throws InterruptedException, ExecutionException {
        latch.await();
        if (error != null) {
            throw new RuntimeException("execution exception", error);
        }
        return result;
    }

    // 暂未考虑 cancel 情况
    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        try {
            if (latch.await(timeout, unit)) {
                if (error != null) {
                    throw new RuntimeException("call future get exception", error);
                }
                return result;
            } else {
                throw new RuntimeException("async get time out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("call future is interuptted", e);
        }
    }
}
