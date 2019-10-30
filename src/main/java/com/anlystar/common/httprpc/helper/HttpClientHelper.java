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
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public abstract class HttpClientHelper {

    /**
     * jackson
     */
    protected final static ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /**
     * http client
     */
    protected final static CloseableHttpClient HTTP_CLIENT;
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

    protected static Logger logger = LoggerFactory.getLogger(HttpClientHelper.class);

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

        HTTP_CLIENT = HttpClientBuilder.create().build();

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
    public static String get(String url) throws Exception {
        return get(url, null, null, null);
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
    public static String get(String url, Map<String, String> pars) throws Exception {
        return get(url, null, pars, null);
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
    public static String get(String url, Map<String, String> headers, Map<String, String> pars) throws Exception {
        return get(url, headers, pars, null);
    }

    /**
     * 处理 GET 请求
     *
     * @param url
     * @param headers
     * @param pars
     * @param charset
     *
     * @return
     *
     * @throws Exception
     */
    public static String get(String url, Map<String, String> headers, Map<String, String> pars, String charset)
            throws Exception {
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
        // 将上面的配置信息 运用到这个Get请求里
        httpGet.setConfig(REQUEST_CONFIG);

        if (headers != null && !headers.isEmpty()) {
            headers.forEach(httpGet::setHeader);
        }

        httpGet.setHeader("Accept-Encoding", "gzip, deflate");

        // 响应模型
        try (CloseableHttpResponse response = HTTP_CLIENT.execute(httpGet)) {
            return parseResponse(response);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw e;
        }
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
    public static String post(String url, Map<String, String> pars) throws Exception {
        return post(url, null, pars, null);
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
    public static String post(String url, Map<String, String> headers, Map<String, String> pars) throws Exception {
        return post(url, headers, pars, null);
    }

    /**
     * 处理 POST 请求
     *
     * @param url
     * @param headers
     * @param pars
     * @param charset
     *
     * @return
     *
     * @throws Exception
     */
    public static String post(String url, Map<String, String> headers, Map<String, String> pars, String charset)
            throws Exception {
        // 参数
        StringJoiner joiner = new StringJoiner("&");

        if (pars != null && !pars.isEmpty()) {
            pars.forEach((k, v) -> {
                if (v != null) {
                    joiner.add(k + "=" + v);
                }
            });
        }

        // 创建Post请求
        HttpPost httpPost = new HttpPost(url);
        // 将上面的配置信息 运用到这个Post请求里
        httpPost.setConfig(REQUEST_CONFIG);

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
        try (CloseableHttpResponse response = HTTP_CLIENT.execute(httpPost)) {
            return parseResponse(response);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw e;
        }
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
    public static String postJson(String url, Object json) throws Exception {
        return postJson(url, null, json, null);
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
    public static String postJson(String url, Map<String, String> headers, Object json) throws Exception {
        return postJson(url, headers, json, null);
    }

    /**
     * 处理 PostJson 请求
     *
     * @param url
     * @param headers
     * @param json
     * @param charset
     *
     * @return
     *
     * @throws Exception
     */
    public static String postJson(String url, Map<String, String> headers, Object json, String charset)
            throws Exception {

        // 创建POST请求
        HttpPost httpPost = new HttpPost(url);
        // 将上面的配置信息 运用到这个POST请求里
        httpPost.setConfig(REQUEST_CONFIG);

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
        try (CloseableHttpResponse response = HTTP_CLIENT.execute(httpPost)) {
            return parseResponse(response);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw e;
        }
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

    protected static String parseResponse(HttpResponse response) throws IOException {
        if (HttpStatus.SC_OK == response.getStatusLine().getStatusCode()) {
            return EntityUtils.toString(response.getEntity(), getCharset(null, response));
        } else {
            throw new RuntimeException(
                    "http status error: " + response.getStatusLine().getStatusCode());
        }
    }

}
