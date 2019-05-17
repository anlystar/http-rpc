/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.anlystar.common.httprpc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.core.annotation.AliasFor;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface URL {

    /**
     * 请求url
     *
     * @return
     */
    String value() default "";

    /**
     * 请求url
     *
     * @return
     */
    @AliasFor("name") String name() default "";

    /**
     * 默认地址
     * @return
     */
    String defaultUrl() default "";

}
