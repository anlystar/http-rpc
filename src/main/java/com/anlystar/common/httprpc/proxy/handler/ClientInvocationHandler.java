/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.anlystar.common.httprpc.proxy.handler;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.concurrent.FutureCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import com.anlystar.common.helper.AsyncHttpClientHelper;
import com.anlystar.common.httprpc.annotation.Async;
import com.anlystar.common.httprpc.annotation.HttpMethod;
import com.anlystar.common.httprpc.annotation.PathVariable;
import com.anlystar.common.httprpc.annotation.RequestBody;
import com.anlystar.common.httprpc.annotation.RequestMethod;
import com.anlystar.common.httprpc.annotation.RpcParam;
import com.anlystar.common.httprpc.annotation.URL;
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

    private Logger logger = LoggerFactory.getLogger(getClass());

    private Environment env;

    public ClientInvocationHandler(Environment env) {
        this.env = env;
    }

    @Override
    protected Object handleInvocation(Object proxy, Method method, Object[] args) throws Throwable {

        Type returnType = method.getGenericReturnType();

        Async async = method.getAnnotation(Async.class);
        if (async != null && !returnType.getClass().equals(Void.class)) {
            throw new IllegalArgumentException("不支持的返回值");
        }

        HttpMethod httpMethod = method.getAnnotation(HttpMethod.class);

        RequestMethod requestMethod = RequestMethod.GET;

        if (httpMethod != null) {
            requestMethod = httpMethod.value();
        }

        String requestUrl = getUrl(method);

        if (StringUtils.isBlank(requestUrl)) {
            throw new IllegalArgumentException("未配置URL地址参数");
        }

        Map<String, String> pars = processPars(proxy, method, args);

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

        if (async == null) {
            String res = execute(requestMethod, requestUrl, pars, args);
            return OBJECT_MAPPER.readValue(res, TYPE_FACTORY.constructType(method.getGenericReturnType()));
        } else {
            executeAsync(requestMethod, requestUrl, pars, args);
            return null;
        }
    }

    protected void executeAsync(RequestMethod requestMethod, String requestUrl,
                                Map<String, String> pars, Object[] args) {
        String response = "";

        long start = System.currentTimeMillis();

        String parstr = "";
        try {

            if (logger.isInfoEnabled()) {
                parstr = OBJECT_MAPPER.writeValueAsString(pars);
            }

            FutureCallback<HttpResponse> callback = new FutureCallback<HttpResponse>() {
                @Override
                public void completed(HttpResponse result) {

                }

                @Override
                public void failed(Exception ex) {
                    logger.error(ex.getMessage(), ex);
                }

                @Override
                public void cancelled() {

                }
            };

            if (requestMethod == RequestMethod.GET) {
                AsyncHttpClientHelper.get(requestUrl, pars, callback);
            } else if (requestMethod == RequestMethod.POST) {
                AsyncHttpClientHelper.post(requestUrl, pars, callback);
            } else {
                AsyncHttpClientHelper.postJson(requestUrl, args[0], callback);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        } finally {
            long end = System.currentTimeMillis();
            logger.info("RPC ==> url: {}, pars:{} result: {}, cost: {}ms", requestUrl,
                    parstr, response, end - start);
        }

    }

    protected String execute(RequestMethod requestMethod, String requestUrl,
                             Map<String, String> pars, Object[] args) {

        String response = "";
        String parstr = "";
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
            throw new RuntimeException(e);
        } finally {
            long end = System.currentTimeMillis();
            logger.info("RPC ==> url: {}, pars:{} result: {}, cost: {}ms", requestUrl,
                    parstr, response, end - start);
        }

        return response;
    }

    protected Map<String, String> processPars(Object proxy, Method method, Object[] args) throws Throwable {

        HttpMethod httpMethod = method.getAnnotation(HttpMethod.class);

        Parameter[] parameters = method.getParameters();

        RequestMethod requestMethod = RequestMethod.GET;

        if (httpMethod != null) {
            requestMethod = httpMethod.value();
        }

        Map<String, String> pars = null;

        if (parameters != null && parameters.length > 0) {
            if (requestMethod == RequestMethod.GET || requestMethod == RequestMethod.POST) {
                pars = Maps.newHashMap();
                for (int i = 0, len = parameters.length; i < len; i++) {
                    Parameter p = parameters[i];
                    if (p == null || args[i] == null) {
                        continue;
                    }

                    if (len == 1) {
                        RequestBody requestBody = p.getAnnotation(RequestBody.class);
                        if (requestBody != null) {
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
                        break;
                    }

                    if (args[i] instanceof Collection) {
                        RpcParam rpcParam = p.getAnnotation(RpcParam.class);
                        if (rpcParam == null) {
                            throw new IllegalArgumentException("@RpcParam注解不能为空");
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
                        if (rpcParam != null) {
                            String value = args[i] == null ? "" : (args[i] + "");
                            pars.put(rpcParam.value(), value);
                            continue;
                        }

                    } else {
                        throw new IllegalArgumentException("不支持的参数类型 -> " + args[i].getClass());
                    }

                }
            }
        }

        return pars;
    }

    /**
     * 获取 请求url
     *
     * @param method
     *
     * @return
     */
    protected String getUrl(Method method) {

        String requestUrl = null;

        URL url = method.getAnnotation(URL.class);
        if (url == null) {
            return requestUrl;
        }

        requestUrl = env.getProperty(url.value());

        if (requestUrl == null || "".equals(requestUrl)) {
            requestUrl = url.defaultUrl();
        }

        return requestUrl;

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

}
