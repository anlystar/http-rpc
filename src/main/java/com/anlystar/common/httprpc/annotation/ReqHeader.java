/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.anlystar.common.httprpc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Created by anliyong on 19/10/24.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ReqHeaders.class)
public @interface ReqHeader {

    /**
     * header 参数名
     *
     * @return
     */
    String key() default "";

    /**
     * header 值
     *
     * @return
     */
    String value() default "";
}
