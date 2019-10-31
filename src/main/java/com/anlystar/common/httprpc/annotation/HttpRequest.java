/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.anlystar.common.httprpc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpRequest {

    /**
     * 支持的请求方法
     *
     * @return
     */
    RequestMethod method() default RequestMethod.GET;

    /**
     * 是否异步请求
     *
     * @return
     */
    boolean async() default false;

    /**
     * 请求的 URL
     * @return
     */
    String url() default "";

    /**
     * 从Spring上下文中获取 URL 的 key
     * @return
     */
    String urlKey() default "";

}
