/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.anlystar.common.httprpc.helper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public abstract class AsyncHttpClientHelper {

    /**
     * jackson
     */
    protected final static ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /**
     * 默认编码
     */
    protected final static String DEFAULT_CHARSET = "UTF-8";
    private static Logger logger = LoggerFactory.getLogger(AsyncHttpClientHelper.class);
    private static int TIMEOUT = 60 * 1000;
    private static int POOL_SIZE = 20;
    private static int BUF_SIZE = 8192;

    static {
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        OBJECT_MAPPER.setDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 创建一个默认的 AsyncHttpHelper
     *
     * @return
     */
    public static CloseableHttpAsyncClient createDefaultHttpAsyncClient() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setContentCompressionEnabled(true)
                // 设置连接超时时间(单位毫秒)
                .setConnectTimeout(TIMEOUT)
                // 设置请求超时时间(单位毫秒)
                .setConnectionRequestTimeout(TIMEOUT)
                // socket读写超时时间(单位毫秒)
                .setSocketTimeout(TIMEOUT)
                // 设置是否允许重定向(默认为true)
                .setRedirectsEnabled(true).build();
        IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
                .setIoThreadCount(Runtime.getRuntime().availableProcessors())
                .setSoKeepAlive(true)
                .setConnectTimeout(TIMEOUT)
                .setRcvBufSize(BUF_SIZE)
                .setSndBufSize(BUF_SIZE)
                .build();
        // 设置连接池大小
        ConnectingIOReactor ioReactor;
        // 连接池
        PoolingNHttpClientConnectionManager connManager;
        try {
            ioReactor = new DefaultConnectingIOReactor(ioReactorConfig);
            connManager = new PoolingNHttpClientConnectionManager(ioReactor);
            connManager.setMaxTotal(POOL_SIZE);
            connManager.setDefaultMaxPerRoute(POOL_SIZE);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

        CloseableHttpAsyncClient httpAsyncClient = HttpAsyncClients.custom()
                .setConnectionManager(connManager)
                .setDefaultRequestConfig(requestConfig).build();
        httpAsyncClient.start();
        return httpAsyncClient;
    }

    /**
     * 创建一个自定义的 AsyncHttpHelper
     *
     * @param timeout
     * @param poolSize
     *
     * @return
     */
    public static CloseableHttpAsyncClient createHttpAsyncClient(int timeout, int poolSize) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setContentCompressionEnabled(true)
                .setConnectTimeout(timeout)
                .setSocketTimeout(timeout)
                .setConnectionRequestTimeout(timeout)
                .build();
        IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
                .setIoThreadCount(Runtime.getRuntime().availableProcessors())
                .setSoKeepAlive(true)
                .setConnectTimeout(timeout)
                .setRcvBufSize(8192)
                .setSndBufSize(8192)
                .build();
        // 设置连接池大小
        ConnectingIOReactor ioReactor;
        // 连接池
        PoolingNHttpClientConnectionManager connManager;
        try {
            ioReactor = new DefaultConnectingIOReactor(ioReactorConfig);
            connManager = new PoolingNHttpClientConnectionManager(ioReactor);
            connManager.setMaxTotal(poolSize);
            connManager.setDefaultMaxPerRoute(poolSize);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

        CloseableHttpAsyncClient httpAsyncClient = HttpAsyncClients.custom()
                .setConnectionManager(connManager)
                .setDefaultRequestConfig(requestConfig)
                .build();
        httpAsyncClient.start();
        return httpAsyncClient;
    }

    /**
     * 处理 GET 请求
     *
     * @param url
     *
     * @return
     *
     * @throws Exception
     */
    public static void get(String url, final FutureCallback<HttpResponse> callback) {
        get(DefaultInstanceHolder.HTTP_CLIENT, url, null, null, callback);
    }

    /**
     * 处理 GET 请求
     *
     * @param url
     * @param pars
     *
     * @return
     *
     * @throws Exception
     */
    public static void get(String url, Map<String, String> pars, final FutureCallback<HttpResponse> callback) {
        get(DefaultInstanceHolder.HTTP_CLIENT, url, null, pars, callback);
    }

    /**
     * 处理 GET 请求
     *
     * @param url
     * @param pars
     *
     * @return
     *
     * @throws Exception
     */
    public static void get(String url, Map<String, String> headers, Map<String, String> pars,
                           final FutureCallback<HttpResponse> callback) {
        get(DefaultInstanceHolder.HTTP_CLIENT, url, headers, pars, callback);
    }

    /**
     * 处理 GET 请求
     *
     * @param url
     *
     * @return
     *
     * @throws Exception
     */
    public static void get(CloseableHttpAsyncClient client, String url, final FutureCallback<HttpResponse> callback) {
        get(client, url, null, null, callback);
    }

    /**
     * 处理 GET 请求
     *
     * @param url
     * @param pars
     *
     * @return
     *
     * @throws Exception
     */
    public static void get(CloseableHttpAsyncClient client, String url, Map<String, String> pars,
                           final FutureCallback<HttpResponse> callback) {
        get(client, url, null, pars, callback);
    }

    /**
     * 处理 GET 请求
     *
     * @param url
     * @param headers
     * @param pars
     * @param callback
     *
     * @return
     *
     * @throws Exception
     */
    public static void get(CloseableHttpAsyncClient client, String url, Map<String, String> headers,
                           Map<String, String> pars, final FutureCallback<HttpResponse> callback) {
        // 参数
        StringJoiner joiner = new StringJoiner("&");

        if (pars != null && !pars.isEmpty()) {
            pars.forEach((k, v) -> {
                if (v == null) {
                    return;
                }
                try {
                    joiner.add(String.format("%s=%s", k, URLEncoder.encode(v, "utf-8")));
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            });
        }

        // 创建Get请求
        HttpGet httpGet = new HttpGet(url + "?" + joiner.toString());

        if (headers != null && !headers.isEmpty()) {
            headers.forEach(httpGet::setHeader);
        }

        httpGet.setHeader("Accept-Encoding", "gzip, deflate");
        httpGet.setHeader("Accept-Encoding", "gzip, deflate");
        client.execute(httpGet, callback);
    }

    /**
     * 处理 POST 请求
     *
     * @param url
     * @param pars
     *
     * @return
     *
     * @throws Exception
     */
    public static void post(String url, Map<String, String> pars,
                            final FutureCallback<HttpResponse> callback) {
        post(DefaultInstanceHolder.HTTP_CLIENT, url, null, pars, callback);
    }


    /**
     * 处理 POST 请求
     *
     * @param url
     * @param pars
     *
     * @return
     *
     * @throws Exception
     */
    public static void post(String url, Map<String, String> headers, Map<String, String> pars,
                            final FutureCallback<HttpResponse> callback) {
        post(DefaultInstanceHolder.HTTP_CLIENT, url, headers, pars, callback);
    }

    /**
     * 处理 POST 请求
     *
     * @param url
     * @param pars
     *
     * @return
     *
     * @throws Exception
     */
    public static void post(CloseableHttpAsyncClient client, String url, Map<String, String> pars,
                            final FutureCallback<HttpResponse> callback) {
        post(client, url, null, pars, callback);
    }

    /**
     * 处理 POST 请求
     *
     * @param url
     * @param headers
     * @param pars
     *
     * @return
     *
     * @throws Exception
     */
    public static void post(CloseableHttpAsyncClient client, String url, Map<String, String> headers,
                            Map<String, String> pars, final FutureCallback<HttpResponse> callback) {
        // 参数
        StringJoiner joiner = new StringJoiner("&");

        if (pars != null && !pars.isEmpty()) {
            pars.forEach((k, v) -> {
                if (v != null) {
                    joiner.add(k + "=" + v);
                }
            });
        }

        // 创建Get请求
        HttpPost httpPost = new HttpPost(url);

        if (headers != null && !headers.isEmpty()) {
            headers.forEach(httpPost::setHeader);
        }

        // 设置ContentType
        httpPost.setHeader("Content-Type", "application/x-www-form-urlencoded;charset=utf8");
        httpPost.setHeader("Accept-Encoding", "gzip, deflate");
        StringEntity entity = new StringEntity(joiner.toString(), "utf-8");
        entity.setContentEncoding("UTF-8");
        httpPost.setEntity(entity);
        // 响应模型
        client.execute(httpPost, callback);
    }

    /**
     * 处理 PostJson 请求
     *
     * @param url
     * @param json
     *
     * @return
     *
     * @throws Exception
     */
    public static void postJson(String url, Object json,
                                final FutureCallback<HttpResponse> callback) throws Exception {
        postJson(DefaultInstanceHolder.HTTP_CLIENT, url, null, json, callback);
    }

    /**
     * 处理 PostJson 请求
     *
     * @param url
     * @param json
     *
     * @return
     *
     * @throws Exception
     */
    public static void postJson(String url, Map<String, String> headers, Object json,
                                final FutureCallback<HttpResponse> callback) throws Exception {
        postJson(DefaultInstanceHolder.HTTP_CLIENT, url, headers, json, callback);
    }

    /**
     * 处理 PostJson 请求
     *
     * @param url
     * @param json
     *
     * @return
     *
     * @throws Exception
     */
    public static void postJson(CloseableHttpAsyncClient client, String url, Object json,
                                final FutureCallback<HttpResponse> callback) throws Exception {
        postJson(client, url, null, json, callback);
    }

    /**
     * 处理 PostJson 请求
     *
     * @param url
     * @param headers
     * @param json
     * @param callback
     *
     * @return
     *
     * @throws Exception
     */
    public static void postJson(CloseableHttpAsyncClient client, String url, Map<String, String> headers, Object json,
                                final FutureCallback<HttpResponse> callback) throws Exception {

        // 创建Get请求
        HttpPost httpPost = new HttpPost(url);

        if (headers != null && !headers.isEmpty()) {
            headers.forEach(httpPost::setHeader);
        }

        // 设置ContentType
        httpPost.setHeader("Content-Type", "application/json;charset=utf8");
        httpPost.setHeader("Accept-Encoding", "gzip, deflate");
        StringEntity entity = new StringEntity(OBJECT_MAPPER.writeValueAsString(json), "utf-8");
        entity.setContentEncoding("UTF-8");
        httpPost.setEntity(entity);

        // 响应模型
        client.execute(httpPost, callback);
    }

    protected static String getCharset(String charset, HttpResponse response) {

        if (charset != null && !"".equals(charset)) {
            return charset;
        }

        Header contentType = response.getFirstHeader("Content-Type");
        if (contentType != null) {
            charset = getCharsetFromContentType(contentType.getValue());
        }

        return charset == null ? DEFAULT_CHARSET : charset;
    }

    protected static String getCharsetFromContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        Pattern charsetPattern = Pattern.compile("(?i)\\bcharset=\\s*\"?([^\\s;\"]*)");
        Matcher m = charsetPattern.matcher(contentType);
        if (m.find()) {
            String charset = m.group(1).trim();
            if (Charset.isSupported(charset)) {
                return charset;
            }
            charset = charset.toUpperCase(Locale.ENGLISH);
            if (Charset.isSupported(charset)) {
                return charset;
            }
        }
        return null;
    }

    public static String parseResponse(HttpResponse response) throws IOException {
        if (HttpStatus.SC_OK == response.getStatusLine().getStatusCode()) {
            return EntityUtils.toString(response.getEntity(), getCharset(null, response));
        } else {
            throw new RuntimeException(
                    "http status error: " + response.getStatusLine().getStatusCode());
        }
    }

    private static class DefaultInstanceHolder {
        private static CloseableHttpAsyncClient HTTP_CLIENT = createDefaultHttpAsyncClient();
    }

}
