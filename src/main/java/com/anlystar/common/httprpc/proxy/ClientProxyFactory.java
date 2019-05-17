/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.anlystar.common.httprpc.proxy;

import org.springframework.core.env.Environment;

import com.anlystar.common.httprpc.proxy.handler.ClientInvocationHandler;
import com.google.common.reflect.Reflection;

/**
 * Created by anliyong on 18/8/23.
 */
public abstract class ClientProxyFactory {

    public static <T> T createServiceProxy(Class<T> clientInterface, Environment env) {
        return Reflection.newProxy(clientInterface, new ClientInvocationHandler(env));
    }

}
