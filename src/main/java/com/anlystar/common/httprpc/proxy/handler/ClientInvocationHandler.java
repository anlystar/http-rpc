/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.anlystar.common.httprpc.proxy.handler;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.Future;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.utils.DateUtils;
import org.apache.http.concurrent.FutureCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import com.anlystar.common.helper.RSAHelper;
import com.anlystar.common.httprpc.annotation.CallFunction;
import com.anlystar.common.httprpc.annotation.PathVariable;
import com.anlystar.common.httprpc.annotation.HttpRequest;
import com.anlystar.common.httprpc.annotation.ReqSign;
import com.anlystar.common.httprpc.annotation.RequestBody;
import com.anlystar.common.httprpc.annotation.RequestMethod;
import com.anlystar.common.httprpc.annotation.ReqHeader;
import com.anlystar.common.httprpc.annotation.ReqParam;
import com.anlystar.common.httprpc.callback.CallbackFuture;
import com.anlystar.common.httprpc.helper.AsyncHttpClientHelper;
import com.anlystar.common.httprpc.helper.HttpClientHelper;
import com.anlystar.common.httprpc.helper.ValidationHelper;
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
 * Post 提交方式 方法参数必须带 {@link ReqParam} 或 {@link PathVariable} 注解， 不带该注解的不解析
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

        HttpRequest httpRequest = method.getAnnotation(HttpRequest.class);

        if (httpRequest.async() && !("void".equals(returnType.getName())
                                           || returnType.equals(Future.class)
                                           || Future.class.isAssignableFrom(returnType))) {
            throw new IllegalArgumentException("不支持的返回值");
        }

        if ("".equals(httpRequest.url()) && "".equals(httpRequest.urlKey())) {
            throw new IllegalArgumentException("未配置URL信息");
        }

        ValidationHelper.validateParameters(proxy, method, args);

        String requestUrl = getRequestUrl(method, args, httpRequest);

        RequestMethod requestMethod = httpRequest.method();

        Object pars = processPars(method, args);

        Map<String, String> headers = processHeaders(method, args, pars);

        if (!httpRequest.async()) {
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

    protected String getRequestUrl(Method method, Object[] args, HttpRequest httpRequest) {

        String requestUrl = httpRequest.url();

        if ("".equals(requestUrl)) {
            requestUrl = env.getProperty(httpRequest.urlKey(), System.getProperty(httpRequest.urlKey()));
        }

        if (requestUrl == null || "".equals(requestUrl)) {
            throw new IllegalArgumentException("未查询到 URL 配置信息, key => " + httpRequest.urlKey());
        }

        StringJoiner parsJoiner = new StringJoiner("&");

        Parameter[] parameters = method.getParameters();
        if (parameters != null && parameters.length > 0) {
            for (int i = 0, len = parameters.length; i < len; i++) {
                Parameter p = parameters[i];
                if (args[i] == null) {
                    continue;
                }
                if ((args[i] instanceof String || isWrapClass(args[i].getClass()))) {
                    String value = args[i] == null ? "" : (args[i] + "");
                    PathVariable pathVariable = p.getAnnotation(PathVariable.class);
                    if (pathVariable != null) {
                        requestUrl = requestUrl.replace(String.format("{%s}", pathVariable.value()), value);
                    }
                    ReqParam reqParam = p.getAnnotation(ReqParam.class);
                    if (reqParam != null && reqParam.url()) {
                        try {
                            parsJoiner.add(URLEncoder.encode(reqParam.name(), "utf-8") + "=" + URLEncoder.encode(value,
                                    "utf-8"));
                        } catch (Exception e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                } else if (args[i] instanceof Date){
                    ReqParam reqParam = p.getAnnotation(ReqParam.class);
                    if (reqParam != null && !reqParam.header() && !reqParam.url()) {
                        String value = DateUtils.formatDate((Date) args[i], reqParam.format());
                        try {
                            parsJoiner.add(URLEncoder.encode(reqParam.name(), "utf-8") + "=" + URLEncoder.encode(value,
                                    "utf-8"));
                        } catch (Exception e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }
            }
        }

        if (parsJoiner.length() > 0) {
            if (requestUrl.endsWith("?")) {
                requestUrl = requestUrl + parsJoiner.toString();
            } else {
                requestUrl = requestUrl + "?" + parsJoiner.toString();
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
                AsyncHttpClientHelper.post(requestUrl, headers, (Map<String, String>) pars, callback);
            } else {
                AsyncHttpClientHelper.postJson(requestUrl, headers, pars, callback);
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

    protected Map<String, String> processHeaders(Method method, Object[] args, Object pars) throws Throwable {

        Map<String, String> headers = new HashMap<>();

        ReqHeader[] reqHeaders = method.getDeclaringClass().getAnnotationsByType(ReqHeader.class);

        if (reqHeaders != null && reqHeaders.length > 0) {
            for (ReqHeader reqHeader : reqHeaders) {
                headers.put(reqHeader.key(), reqHeader.value());
            }
        }

        reqHeaders = method.getAnnotationsByType(ReqHeader.class);

        if (reqHeaders != null && reqHeaders.length > 0) {
            for (ReqHeader reqHeader : reqHeaders) {
                headers.put(reqHeader.key(), reqHeader.value());
            }
        }

        String sign = null;
        ReqSign reqSign = null;

        Parameter[] parameters = method.getParameters();
        if (parameters == null || parameters.length == 0) {
            return headers;
        }

        for (int i = 0, len = parameters.length; i < len; i++) {
            Parameter p = parameters[i];
            if (p == null || args[i] == null) {
                continue;
            }

            RequestBody requestBody = p.getAnnotation(RequestBody.class);
            if (requestBody != null) {
                if (!requestBody.header()) {
                    continue;
                }
                headers.putAll(convert2Map((BaseModel) args[i]));
            } else if (args[i] instanceof String && p.getAnnotation(ReqSign.class) != null) {
                sign = (String) args[i];
                reqSign = p.getAnnotation(ReqSign.class);
            } else if ((args[i] instanceof String || isWrapClass(args[i].getClass()))) {
                ReqParam reqParam = p.getAnnotation(ReqParam.class);
                if (reqParam != null && reqParam.header()) {
                    String value = args[i] == null ? "" : (args[i] + "");
                    headers.put(reqParam.value(), value);
                }
            } else if (args[i] instanceof Date){
                ReqParam reqParam = p.getAnnotation(ReqParam.class);
                if (reqParam != null && !reqParam.header() && !reqParam.url()) {
                    String value = DateUtils.formatDate((Date) args[i], reqParam.format());
                    headers.put(reqParam.value(), value);
                }
            } else {
                throw new IllegalArgumentException("不支持的参数类型 -> " + args[i].getClass());
            }
        }

        if (sign != null && reqSign != null) {
            HttpRequest httpRequest = method.getAnnotation(HttpRequest.class);
            String stamp = headers.get("stamp");
            if (httpRequest.method() == RequestMethod.POSTJSON) {
                headers.put(reqSign.name(), rasSign(pars, stamp, sign));
            } else {
                headers.put(reqSign.name(), rasFormSign((Map<String, String>) pars, stamp, sign));
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

        HttpRequest httpRequest = method.getAnnotation(HttpRequest.class);
        RequestMethod requestMethod = httpRequest.method();

        if (parameters == null || parameters.length < 1) {
            return pars;
        }

        if (requestMethod == RequestMethod.POSTJSON) {
            return args[0];
        }

        pars = Maps.newHashMap();
        for (int i = 0, len = parameters.length; i < len; i++) {
            Parameter p = parameters[i];
            if (p == null || args[i] == null || p.getAnnotation(ReqHeader.class) != null) {
                continue;
            }

            RequestBody requestBody = p.getAnnotation(RequestBody.class);
            if (requestBody != null && !requestBody.header()) {
                pars.putAll(convert2Map((BaseModel) args[i]));
            }

            if (args[i] instanceof Collection) {
                ReqParam reqParam = p.getAnnotation(ReqParam.class);
                if (reqParam == null || reqParam.header()) {
                    continue;
                }

                Collection c = (Collection) args[i];
                Iterator ite = c.iterator();
                while (ite.hasNext()) {
                    Object o = ite.next();
                    if (o instanceof BaseModel) {
                        pars.putAll(convert2Map((BaseModel) o));
                    } else {
                        String k = reqParam.value();
                        String value = convertValue(o);
                        if (pars.containsKey(k)) {
                            pars.put(k, pars.get(k) + "," + value);
                        } else {
                            pars.put(k, value);
                        }
                    }
                }

            } else if (args[i] instanceof BaseModel) {
                pars.putAll(convert2Map((BaseModel) args[i]));
            } else if ((args[i] instanceof String || isWrapClass(args[i].getClass()))) {
                ReqParam reqParam = p.getAnnotation(ReqParam.class);
                if (reqParam != null && !reqParam.header() && !reqParam.url()) {
                    String value = args[i] == null ? "" : (args[i] + "");
                    pars.put(reqParam.value(), value);
                }
            } else if (args[i] instanceof Date){
                ReqParam reqParam = p.getAnnotation(ReqParam.class);
                if (reqParam != null && !reqParam.header() && !reqParam.url()) {
                    String value = DateUtils.formatDate((Date) args[i], reqParam.format());
                    pars.put(reqParam.value(), value);
                }
            } else {
                throw new IllegalArgumentException("不支持的参数类型 -> " + args[i].getClass());
            }
        }

        return pars;
    }

    protected String rasFormSign(Map<String, String> pars, String stamp, String privateKey) {
        StringJoiner joiner = new StringJoiner("&");
        if (MapUtils.isNotEmpty(pars)) {
            List<String> keys = new ArrayList<>(pars.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                joiner.add(String.format("%s=%s", key, pars.get(key)));
            }
        }
        if (stamp != null) {
            joiner.add(stamp + "");
        }
        return RSAHelper.sign(joiner.toString(), privateKey);
    }

    protected String rasSign(Object requestBody, String stamp, String privateKey) {
        StringJoiner joiner = new StringJoiner("&");
        if (requestBody != null) {
            try {
                joiner.add(OBJECT_MAPPER.writeValueAsString(requestBody));
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
        if (stamp != null) {
            joiner.add(stamp + "");
        }
        return RSAHelper.sign(joiner.toString(), privateKey);
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

    protected Map<String, String> convert2Map(BaseModel baseModel) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(baseModel);
            Map<String, Object> map = OBJECT_MAPPER.readValue(json, HashMap.class);
            Map<String, String> ret = new HashMap<>();
            map.forEach((k, v) -> ret.put(k, convertValue(v)));
            return ret;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

        return new HashMap<>();
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
