/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.anlystar.common.httprpc.helper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.executable.ExecutableValidator;

import org.apache.commons.lang3.StringUtils;

/**
 * Created by anliyong on 2019/5/26.
 */
public abstract class ValidationHelper {

    public abstract class ValidationHelper {

        private static ExecutableValidator EXECUTABLE_VALIDATOR =
                Validation.buildDefaultValidatorFactory().getValidator().forExecutables();

        private static Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

        /**
         * 注解验证参数
         * @param obj
         * @param <T>
         */
        public static <T> void validateParameters(T obj, Method method, Object[] params) {
            Set<ConstraintViolation<T>> constraintViolations = EXECUTABLE_VALIDATOR.validateParameters(obj, method, params);
            // 抛出检验异常
            if (constraintViolations != null && constraintViolations.size() > 0) {
                List<String> messages = constraintViolations.stream()
                        .map(ConstraintViolation::getMessage).collect(Collectors.toList());
                throw new IllegalArgumentException(StringUtils.join(messages, ","));
            }
        }

        /**
         * 注解验证参数
         * @param object
         * @param <T>
         */
        public static <T> void validate(T object, Class<?>... groups) {
            Set<ConstraintViolation<T>> constraintViolations = VALIDATOR.validate(object, groups);
            // 抛出检验异常
            if (constraintViolations != null && constraintViolations.size() > 0) {
                List<String> messages = constraintViolations.stream()
                        .map(ConstraintViolation::getMessage).collect(Collectors.toList());
                throw new IllegalArgumentException(StringUtils.join(messages, ","));
            }
        }

    }

}
