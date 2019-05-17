/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.anlystar.common.httprpc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.core.annotation.AliasFor;

/**
 * Created by anliyong on 18/8/23.
 */
@Target({ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface RpcParam {

    /**
     * 请求参数名
     *
     * @return
     */
    String value() default "";

    /**
     * 请求参数名
     *
     * @return
     */
    @AliasFor("name") String name() default "";

}
