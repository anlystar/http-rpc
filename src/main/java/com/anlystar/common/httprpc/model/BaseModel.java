/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.anlystar.common.httprpc.model;

import java.io.Serializable;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import com.fasterxml.jackson.databind.ObjectMapper;

public class BaseModel implements Serializable {

    public String toString() {
        return ToStringBuilder.reflectionToString(this, ToStringStyle.DEFAULT_STYLE);
    }

    public String toJsonString() {
        String text;
        try {
            text = new ObjectMapper().writeValueAsString(this);
        } catch (Exception e) {
            text = "{}";
        }
        return text;
    }
}
