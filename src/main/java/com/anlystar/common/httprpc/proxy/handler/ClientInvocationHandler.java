/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.anlystar.common.httprpc.proxy.handler;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.concurrent.FutureCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import com.anlystar.common.httprpc.annotation.CallFunction;
import com.anlystar.common.httprpc.annotation.PathVariable;
import com.anlystar.common.httprpc.annotation.Reference;
import com.anlystar.common.httprpc.annotation.RequestBody;
import com.anlystar.common.httprpc.annotation.RequestMethod;
import com.anlystar.common.httprpc.annotation.RpcHeader;
import com.anlystar.common.httprpc.annotation.RpcParam;
import com.anlystar.common.httprpc.callback.CallbackFuture;
import com.anlystar.common.httprpc.helper.AsyncHttpClientHelper;
import com.anlystar.common.httprpc.helper.HttpClientHelper;
import com.anlystar.common.httprpc.model.BaseModel;
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
@SuppressWarnings("unchecked")
public class ClientInvocationHandler extends AbstractInvocationHandler {

    /**
     * jackson
     */
    protected final static ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /**
     * 超时时间
     */
    protected final static int TIMEOUT = 20000;
    /**
     * 配置信息
     */
    protected final static RequestConfig REQUEST_CONFIG;

    private final static TypeFactory TYPE_FACTORY = TypeFactory.defaultInstance();

    static {
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
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

        Class<?> returnType = method.getReturnType();

        Reference reference = method.getAnnotation(Reference.class);

        if (reference.async() && !("void".equals(returnType.getName())
                                           || returnType.equals(Future.class)
                                           || Future.class.isAssignableFrom(returnType))) {
            throw new IllegalArgumentException("不支持的返回值");
        }

        if ("".equals(reference.url()) && "".equals(reference.urlKey())) {
            throw new IllegalArgumentException("未配置URL信息");
        }

        String requestUrl = getRequestUrl(method, args, reference);

        RequestMethod requestMethod = reference.method();

        Object pars = processPars(method, args);

        Map<String, String> headers = processHeaders(method, args);

        if (!reference.async()) {
            String res = execute(requestMethod, requestUrl, headers, pars);
            return convert(res, method);
        } else {

            boolean hasCallback = hasCallback(method);
            if (hasCallback) {
                asyncExecute(requestMethod, requestUrl, headers, pars, args);
                return null;
            } else {
                CallbackFuture<Object> callbackFuture = new CallbackFuture<>();
                asyncExecute(requestMethod, requestUrl, headers, pars, callbackFuture, method);
                return callbackFuture;
            }
        }
    }

    protected String getRequestUrl(Method method, Object[] args, Reference reference) {

        String requestUrl = reference.url();

        if ("".equals(requestUrl)) {
            requestUrl = env.getProperty(reference.urlKey());
        }

        if (requestUrl == null || "".equals(requestUrl)) {
            throw new IllegalArgumentException("未查询到 URL 配置信息, key => " + reference.urlKey());
        }

        Parameter[] parameters = method.getParameters();
        if (parameters != null && parameters.length > 0) {
            for (int i = 0, len = parameters.length; i < len; i++) {
                Parameter p = parameters[i];
                if (args[i] == null) {
                    continue;
                }
                if ((args[i] instanceof String || isWrapClass(args[i].getClass()))) {
                    PathVariable pathVariable = p.getAnnotation(PathVariable.class);
                    if (pathVariable != null) {
                        String value = args[i] == null ? "" : (args[i] + "");
                        requestUrl = requestUrl.replace(String.format("{%s}", pathVariable.value()), value);
                    }
                }
            }
        }
        return requestUrl;
    }

    protected void asyncExecute(RequestMethod requestMethod, String requestUrl, Map<String, String> headers,
                                Object pars, CallbackFuture<Object> callbackFuture, Method method) {

        long start = System.currentTimeMillis();

        try {

            FutureCallback<HttpResponse> callback = new FutureCallback<HttpResponse>() {
                @Override
                public void completed(HttpResponse response) {
                    try {
                        if (HttpStatus.SC_OK == response.getStatusLine().getStatusCode()) {
                            String res = AsyncHttpClientHelper.parseResponse(response);
                            long end = System.currentTimeMillis();
                            logger.info("Aysnc RPC ==> url: {}, method: {}, header: {}, pars: {}, result: {}, "
                                            + "cost: {}ms", requestUrl,
                                    requestMethod.name(), toJsonString(headers), toJsonString(pars), res, end - start);
                            Object ret = convertAsync(res, method);
                            callbackFuture.handleResult(ret);
                        } else {
                            throw new RuntimeException(
                                    "http status error: " + response.getStatusLine().getStatusCode());
                        }
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                        callbackFuture.handleError(e);
                    }
                }

                @Override
                public void failed(Exception e) {
                    logger.error(e.getMessage(), e);
                    callbackFuture.handleError(e);
                }

                @Override
                public void cancelled() {
                    callbackFuture.cancel(false);
                }
            };

            if (requestMethod == RequestMethod.GET) {
                AsyncHttpClientHelper.get(requestUrl, headers, (Map<String, String>) pars, callback);
            } else if (requestMethod == RequestMethod.POST) {
                AsyncHttpClientHelper.post(requestUrl, (Map<String, String>) pars, callback);
            } else {
                AsyncHttpClientHelper.postJson(requestUrl, pars, callback);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        } finally {
            long end = System.currentTimeMillis();
            logger.info("Aysnc RPC ==> url: {}, method: {}, header: {}, pars:{}, cost: {}ms", requestUrl,
                    requestMethod.name(), toJsonString(headers), toJsonString(pars), end - start);
        }

    }

    protected void asyncExecute(RequestMethod requestMethod, String requestUrl, Map<String, String> headers,
                                Object pars, Object[] args) {

        long start = System.currentTimeMillis();

        try {

            FutureCallback<HttpResponse> callback = (FutureCallback<HttpResponse>) args[args.length - 1];

            if (requestMethod == RequestMethod.GET) {
                AsyncHttpClientHelper.get(requestUrl, headers, (Map<String, String>) pars, callback);
            } else if (requestMethod == RequestMethod.POST) {
                AsyncHttpClientHelper.post(requestUrl, headers, (Map<String, String>) pars, callback);
            } else {
                AsyncHttpClientHelper.postJson(requestUrl, headers, pars, callback);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        } finally {
            long end = System.currentTimeMillis();
            logger.info("Aysnc RPC ==> url: {}, header: {}, pars:{}, cost: {}ms", requestUrl, toJsonString(headers),
                    toJsonString(pars), end - start);
        }

    }

    protected String execute(RequestMethod requestMethod, String requestUrl, Map<String, String> headers, Object pars) {

        String response = "";

        long start = System.currentTimeMillis();

        try {

            if (requestMethod == RequestMethod.GET) {
                response = HttpClientHelper.get(requestUrl, headers, (Map<String, String>) pars);
            } else if (requestMethod == RequestMethod.POST) {
                response = HttpClientHelper.post(requestUrl, headers, (Map<String, String>) pars);
            } else {
                response = HttpClientHelper.postJson(requestUrl, headers, pars);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        } finally {
            long end = System.currentTimeMillis();
            logger.info("RPC ==> url: {}, method: {}, header: {}, pars: {}, result: {}, cost: {}ms", requestUrl,
                    requestMethod.name(), toJsonString(headers), toJsonString(pars), response, end - start);
        }
        return response;

    }

    protected Map<String, String> processHeaders(Method method, Object[] args) throws Throwable {

        Map<String, String> headers = new HashMap<>();

        RpcHeader[] rpcHeaders = method.getAnnotationsByType(RpcHeader.class);

        if (rpcHeaders != null || rpcHeaders.length > 0) {
            for (RpcHeader rpcHeader : rpcHeaders) {
                headers.put(rpcHeader.key(), rpcHeader.value());
            }
        }

        Parameter[] parameters = method.getParameters();
        Reference reference = method.getAnnotation(Reference.class);
        RequestMethod requestMethod = reference.method();

        if (parameters != null && parameters.length > 0) {
            if (requestMethod == RequestMethod.GET || requestMethod == RequestMethod.POST) {
                for (int i = 0, len = parameters.length; i < len; i++) {
                    Parameter p = parameters[i];
                    if (p == null || args[i] == null || p.getAnnotation(RpcHeader.class) != null) {
                        continue;
                    }

                    RequestBody requestBody = p.getAnnotation(RequestBody.class);
                    if (requestBody != null && requestBody.header()) {
                        Map m = BeanUtils.describe(args[0]);
                        Set s = m.keySet();
                        for (Object k : s) {
                            if ("class".equals(k)) {
                                continue;
                            }
                            String value = convertValue(m.get(k));
                            headers.put((String) k, value);
                        }
                    }

                    if ((args[i] instanceof String || isWrapClass(args[i].getClass()))) {
                        RpcParam rpcParam = p.getAnnotation(RpcParam.class);
                        if (rpcParam != null && rpcParam.header()) {
                            String value = args[i] == null ? "" : (args[i] + "");
                            headers.put(rpcParam.value(), value);
                            continue;
                        }
                    } else {
                        throw new IllegalArgumentException("不支持的参数类型 -> " + args[i].getClass());
                    }
                }
            }
        }

        return headers;
    }

    protected Object processPars(Method method, Object[] args) throws Throwable {

        Map<String, String> pars = null;
        Parameter[] parameters = method.getParameters();
        // 处理异步函数
        if (parameters.length > 0) {
            Parameter p = parameters[parameters.length - 1];
            CallFunction function = p.getAnnotation(CallFunction.class);
            if (function != null) {
                if (parameters.length > 1) {
                    parameters = Arrays.copyOfRange(parameters, 0, parameters.length - 1);
                } else {
                    parameters = new Parameter[0];
                }
            }
        }

        Reference reference = method.getAnnotation(Reference.class);
        RequestMethod requestMethod = reference.method();

        if (parameters != null && parameters.length > 0) {
            if (requestMethod == RequestMethod.GET || requestMethod == RequestMethod.POST) {
                pars = Maps.newHashMap();
                for (int i = 0, len = parameters.length; i < len; i++) {
                    Parameter p = parameters[i];
                    if (p == null || args[i] == null || p.getAnnotation(RpcHeader.class) != null) {
                        continue;
                    }

                    RequestBody requestBody = p.getAnnotation(RequestBody.class);
                    if (requestBody != null && !requestBody.header()) {
                        Map m = BeanUtils.describe(args[0]);
                        Set s = m.keySet();
                        for (Object k : s) {
                            if ("class".equals(k)) {
                                continue;
                            }
                            String value = convertValue(m.get(k));
                            pars.put((String) k, value);
                        }
                    }

                    if (args[i] instanceof Collection) {
                        RpcParam rpcParam = p.getAnnotation(RpcParam.class);
                        if (rpcParam == null || rpcParam.header()) {
                            continue;
                        }

                        Collection c = (Collection) args[i];
                        Iterator ite = c.iterator();
                        int j = 0;
                        while (ite.hasNext()) {
                            Object o = ite.next();
                            if (o instanceof BaseModel) {
                                Map m = BeanUtils.describe(o);
                                Set s = m.keySet();
                                for (Object k : s) {
                                    if ("class".equals(k)) {
                                        continue;
                                    }
                                    String value = convertValue(m.get(k));
                                    pars.put(rpcParam.value() + "[" + j + "]." + k, value);
                                }
                            } else {
                                String k = rpcParam.value();
                                String value = convertValue(o);
                                if (pars.containsKey(k)) {
                                    pars.put(k, pars.get(k) + "," + value);
                                } else {
                                    pars.put(k, value);
                                }
                            }
                            j++;
                        }

                    } else if (args[i] instanceof BaseModel) {
                        Map m = BeanUtils.describe(args[i]);
                        Set s = m.keySet();
                        for (Object k : s) {
                            if ("class".equals(k)) {
                                continue;
                            }
                            String value = convertValue(m.get(k));
                            pars.put((String) k, value);
                        }
                    } else if ((args[i] instanceof String || isWrapClass(args[i].getClass()))) {
                        RpcParam rpcParam = p.getAnnotation(RpcParam.class);
                        if (rpcParam != null && !rpcParam.header()) {
                            String value = args[i] == null ? "" : (args[i] + "");
                            pars.put(rpcParam.value(), value);
                            continue;
                        }
                    } else {
                        throw new IllegalArgumentException("不支持的参数类型 -> " + args[i].getClass());
                    }
                }
            } else {
                return args[0];
            }
        }

        return pars;

    }

    protected boolean isWrapClass(Class clz) {
        try {
            return ((Class) clz.getField("TYPE").get(null)).isPrimitive();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 参数值 转换
     *
     * @param obj
     *
     * @return
     */
    protected String convertValue(Object obj) {

        if (obj == null) {
            return null;
        } else if (obj instanceof String || isWrapClass(obj.getClass())) {
            return obj + "";
        } else {
            throw new IllegalArgumentException("参数类型只支持八种基本数据类型以及对应的包装类、String、null");
        }

    }

    protected <T> T convert(String text, Type type) throws IOException {
        return OBJECT_MAPPER.readValue(text, TYPE_FACTORY.constructType(type));
    }

    protected <T> T convert(String text, Class<T> clazz) throws IOException {
        if (String.class.equals(clazz)) {
            return (T) text;
        } else {
            return OBJECT_MAPPER.readValue(text, clazz);
        }
    }

    protected <T> T convert(String text, Method method) throws IOException {

        if ("void".equals(method.getReturnType().getName())) {
            return null;
        } else if (String.class.equals(method.getReturnType())) {
            return (T) convert(text, String.class);
        } else if (method.getGenericReturnType() instanceof ParameterizedType) {
            return (T) convert(text, method.getGenericReturnType());
        } else {
            return (T) convert(text, method.getReturnType());
        }
    }

    protected <T> T convertAsync(String text, Method method) throws IOException {
        if ("void".equals(method.getReturnType().getName())) {
            return null;
        } else if (String.class.equals(method.getReturnType())) {
            return (T) convert(text, String.class);
        } else if (method.getReturnType().equals(Future.class)
                || Future.class.isAssignableFrom(method.getReturnType())) {
            ParameterizedType returnType = (ParameterizedType) method.getGenericReturnType();
            Type actualType = returnType.getActualTypeArguments()[0];
            if (actualType instanceof ParameterizedType) { // 仍然是泛型
                return (T) convert(text, actualType);
            } else {
                if (actualType.equals(String.class)) {
                    return (T) convert(text, String.class);
                } else {
                    return (T) convert(text, actualType.getClass());
                }
            }
        } else if (method.getGenericReturnType() instanceof ParameterizedType) {
            return (T) convert(text, method.getGenericReturnType());
        } else {
            return (T) convert(text, method.getReturnType());
        }
    }

    /**
     * 判断是否自带callback函数参数
     *
     * @param method
     *
     * @return
     */
    protected boolean hasCallback(Method method) {
        Parameter[] parameters = method.getParameters();
        if (parameters.length > 0) {
            Parameter p = parameters[parameters.length - 1];
            CallFunction function = p.getAnnotation(CallFunction.class);
            if (function != null) {
                if ("void".equals(method.getReturnType().getName())) {
                    Type type = p.getParameterizedType();
                    if (type instanceof ParameterizedType) {
                        // 强制转型为带参数的泛型类型，
                        ParameterizedType parameterizedType = (ParameterizedType) type;
                        if (FutureCallback.class.equals(parameterizedType.getRawType())) {
                            Type[] types = parameterizedType.getActualTypeArguments();
                            if (types.length > 0) {
                                Type actualType = types[0];
                                if (HttpResponse.class.equals(actualType)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    protected String toJsonString(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return "";
    }
}
