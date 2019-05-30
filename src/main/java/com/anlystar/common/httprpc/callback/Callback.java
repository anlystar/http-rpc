/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.anlystar.common.httprpc.callback;

/**
 * 异步处理的接口
 * @param <T>
 */
public interface Callback<T> {

    /**
     * 处理成功结果
     * @param result
     */
    void handleResult(T result);

    /**
     * 处理异常
     * @param error
     */
    void handleError(Throwable error);
}
