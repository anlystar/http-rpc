/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.anlystar.common.httprpc.helper;

import java.text.SimpleDateFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;

/**
 * Created by anliyong on 18/8/23.
 */
public abstract class JacksonHelper {

    private static final Logger logger = LoggerFactory.getLogger(JacksonHelper.class);

    private final static ObjectMapper objectMapper = new ObjectMapper();
    private final static ObjectMapper transferMapper = new ObjectMapper();

    static {
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        //        objectMapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        //        objectMapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        objectMapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
        transferMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        transferMapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        transferMapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
        transferMapper.setPropertyNamingStrategy(PropertyNamingStrategy.CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES);
    }

    public static ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * JSON串转换为Java泛型对象
     *
     * @param <T>
     * @param jsonString JSON字符串
     * @param tr TypeReference,例如: new TypeReference< List<FamousUser> >(){}
     *
     * @return List对象列表
     */
    @SuppressWarnings("unchecked")
    public static <T> T toJavaObject(String jsonString, TypeReference<T> tr) {

        if (jsonString == null || "".equals(jsonString)) {
            return null;
        }

        try {
            return (T)objectMapper.readValue(jsonString, tr);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

        return null;
    }

    /**
     * JSON串转换为Java泛型对象
     *
     * @param <T>
     * @param jsonString JSON字符串
     * @param tr TypeReference,例如: new TypeReference< List<FamousUser> >(){}
     *
     * @return List对象列表
     */
    @SuppressWarnings("unchecked")
    public static <T> T toJavaObject(String jsonString, TypeReference<T> tr, boolean transferUnderline) {

        if (jsonString == null || "".equals(jsonString)) {
            return null;
        }

        try {

            if (transferUnderline) {
                return (T)transferMapper.readValue(jsonString, tr);
            } else {
                return (T)objectMapper.readValue(jsonString, tr);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

        return null;
    }

    /**
     * Java对象转Json字符串
     */
    public static String parseObject(Object object) {
        String jsonString = null;
        try {
            jsonString = objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return jsonString;

    }

    /**
     * Json字符串转Java对象
     */
    public static <T> T toJavaObject(String jsonString, Class<T> c, boolean transferUnderline) {

        if (jsonString == null || "".equals(jsonString)) {
            return null;
        }
        try {

            if (transferUnderline) {
                return transferMapper.readValue(jsonString, c);
            } else {
                return objectMapper.readValue(jsonString, c);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return null;
    }

    /**
     * Json字符串转Java对象
     */
    public static <T> T toJavaObject(String jsonString, Class<T> c) {
        return toJavaObject(jsonString, c, false);
    }

    public static Object convertValue(Object val, JavaType type) {
        return objectMapper.convertValue(val, type);
    }

}
