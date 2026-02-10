package com.example.trading.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * JSON工具类（基于Gson）
 */
public class JsonUtils {
    private static final Gson GSON = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd HH:mm:ss")
            .serializeNulls()
            .create();

    /**
     * 对象转JSON字符串
     */
    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    /**
     * JSON字符串转对象
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }
}