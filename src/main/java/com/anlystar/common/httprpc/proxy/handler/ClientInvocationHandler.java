/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.anlystar.common.httprpc.proxy.handler;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.text.SimpleDateFormat;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cglib.beans.BeanMap;
import org.springframework.core.env.Environment;

import com.anlystar.common.helper.HttpClientHelper;
import com.anlystar.common.httprpc.annotation.HttpMethod;
import com.anlystar.common.httprpc.annotation.PathVariable;
import com.anlystar.common.httprpc.annotation.RequestBody;
import com.anlystar.common.httprpc.annotation.RequestMethod;
import com.anlystar.common.httprpc.annotation.RpcParam;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.common.collect.Maps;
import com.google.common.reflect.AbstractInvocationHandler;

/**
 * 简易版的http调用封装类似rpc调用
 * <p>
 * postJson 方式的提交 仅支持一个参数，多个参数会忽略除第一个之外的参数
 * <p>
 * Post 提交方式 方法参数必须带 {@link RpcParam} 或 {@link PathVariable} 注解， 不带该注解的不解析
 * Post 提交方式 参数必须是 String 或 八种基本数据类型，否则抛出异常
 * Created by anliyong on 18/8/23.
 */
public class ClientInvocationHandler extends AbstractInvocationHandler {

    /**
     * jackson
     */
    protected final static ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /**
     * http client
     */
    protected final static CloseableHttpClient HTTP_CLIENT = HttpClientBuilder.create().build();
    /**
     * 超时时间
     */
    protected final static int TIMEOUT = 20000;
    /**
     * 配置信息
     */
    protected final static RequestConfig REQUEST_CONFIG;

    /**
     * 默认编码
     */
    protected final static String DEFAULT_CHARSET = "UTF-8";

    //
    private final static TypeFactory TYPE_FACTORY = TypeFactory.defaultInstance();

    static {
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        OBJECT_MAPPER.setDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));

        REQUEST_CONFIG = RequestConfig.custom()
                // 设置连接超时时间(单位毫秒)
                .setConnectTimeout(TIMEOUT)
                // 设置请求超时时间(单位毫秒)
                .setConnectionRequestTimeout(TIMEOUT)
                // socket读写超时时间(单位毫秒)
                .setSocketTimeout(TIMEOUT)
                // 设置是否允许重定向(默认为true)
                .setRedirectsEnabled(true).build();
    }

    protected Logger logger = LoggerFactory.getLogger(getClass());
    private Environment env;

    public ClientInvocationHandler(Environment env) {
        this.env = env;
    }

    @Override
    protected Object handleInvocation(Object proxy, Method method, Object[] args) throws Throwable {

        HttpMethod httpMethod = method.getAnnotation(HttpMethod.class);

        Parameter[] parameters = method.getParameters();

        RequestMethod requestMethod = RequestMethod.GET;

        if (httpMethod != null) {
            requestMethod = httpMethod.value();
        }

        String key = String.format("RPC.%s", getMethodSign(method)).toLowerCase();

        String requestUrl = env.getProperty(key);

        if (StringUtils.isBlank(requestUrl)) {
            throw new IllegalArgumentException("未配置URL地址参数");
        }

        Map<String, String> pars = null;

        String response = "";

        if (parameters != null && parameters.length > 0) {
            if (requestMethod == RequestMethod.GET || requestMethod == RequestMethod.POST) {
                pars = Maps.newHashMap();
                for (int i = 0, len = parameters.length; i < len; i++) {
                    Parameter p = parameters[i];
                    if (p == null) {
                        continue;
                    }

                    if (len == 1) {
                        RequestBody requestBody = p.getAnnotation(RequestBody.class);
                        if (requestBody != null) {
                            BeanMap beanMap = BeanMap.create(args[0]);
                            for (Object k : beanMap.keySet()) {
                                Object v = beanMap.get(k);
                                if (v != null) {
                                    pars.put(k + "", v + "");
                                }
                            }
                        }
                        break;
                    }

                    if (!(args[i] instanceof String || isWrapClass(args[i].getClass()))) {
                        throw new IllegalArgumentException("参数类型只支持String和八种基本数据类型");
                    }

                    RpcParam rpcParam = p.getAnnotation(RpcParam.class);
                    if (rpcParam != null) {
                        String value = args[i] == null ? "" : (args[i] + "");
                        pars.put(rpcParam.value(), value);
                        continue;
                    }

                    PathVariable pathVariable = p.getAnnotation(PathVariable.class);

                    if (pathVariable != null) {
                        String value = args[i] == null ? "" : (args[i] + "");
                        requestUrl = requestUrl.replace(String.format("{%s}", pathVariable.value()), value);
                    }

                }
            }
        }

        long start = System.currentTimeMillis();

        try {
            if (requestMethod == RequestMethod.GET) {
                response = HttpClientHelper.get(requestUrl, pars);
            } else if (requestMethod == RequestMethod.POST) {
                response = HttpClientHelper.post(requestUrl, pars);
            } else {
                response = HttpClientHelper.postJson(requestUrl, args[0]);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw e;
        } finally {
            long end = System.currentTimeMillis();
            logger.info("RPC ==> url: {}, pars:{} result: {}, cost: {}ms", requestUrl,
                    pars != null ? OBJECT_MAPPER.writeValueAsString(pars) : OBJECT_MAPPER.writeValueAsString(args[0]),
                    response, end - start);
        }

        return OBJECT_MAPPER.readValue(response, TYPE_FACTORY.constructType(method.getGenericReturnType()));
    }

    protected String getMethodSign(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName() + "("
                + getParametersSign(method) + ")";
    }

    protected String getParametersSign(Method method) {

        StringBuilder sb = new StringBuilder("");

        Class<?>[] clazzs = method.getParameterTypes();

        if (clazzs.length > 0) {
            for (int i = 0, len = clazzs.length; i < len; i++) {
                if (i > 0) {
                    sb.append(",");
                }
                Class<?> clazz = clazzs[i];
                sb.append(clazz.getSimpleName());
            }
        }
        return sb.toString();
    }

    protected boolean isWrapClass(Class clz) {
        try {
            return ((Class) clz.getField("TYPE").get(null)).isPrimitive();
        } catch (Exception e) {
            return false;
        }
    }

}
