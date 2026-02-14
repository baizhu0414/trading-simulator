package com.example.trading.util;

import com.example.trading.common.enums.SideEnum;
import com.example.trading.domain.model.Order;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * JSON工具类（修复编译错误+Java 17模块问题）
 */
public class JsonUtils {
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Gson GSON = new GsonBuilder()
            .disableJdkUnsafe()
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeTypeAdapter())
            .registerTypeAdapter(SideEnum.class, new SideEnumTypeAdapter())
            .registerTypeAdapter(BigDecimal.class, new BigDecimalTypeAdapter())
            .setPrettyPrinting()
            .registerTypeHierarchyAdapter(LocalDateTime.class, new LocalDateTimeTypeAdapter())
            .create();

    public static String toJson(Object obj) {
        if (obj == null) {
            return "{}";
        }
        return GSON.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty() || "{}".equals(json)) {
            return null;
        }
        try {
            if (Order.class.isAssignableFrom(clazz)) {
                JsonObject jsonObject = GSON.fromJson(json, JsonObject.class);
                if (jsonObject.has("createTime")) {
                    jsonObject.remove("createTime");
                }
                return GSON.fromJson(jsonObject, clazz);
            }
            return GSON.fromJson(json, clazz);
        } catch (JsonParseException e) {
            logError("JSON解析失败：{}，内容：{}", e.getMessage(), json);
            return null;
        }
    }

    public static <T> T fromJson(String json, TypeToken<T> typeToken) {
        if (json == null || json.isEmpty() || "{}".equals(json)) {
            return null;
        }
        try {
            return GSON.fromJson(json, typeToken.getType());
        } catch (JsonSyntaxException e) {
            logError("JSON解析失败：{}，内容：{}", e.getMessage(), json);
            return null;
        }
    }

    private static void logError(String msg, Object... args) {
        System.err.printf("[JsonUtils] " + msg + "%n", args);
    }

    private static class LocalDateTimeTypeAdapter extends TypeAdapter<LocalDateTime> {
        @Override
        public void write(JsonWriter out, LocalDateTime value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            out.value(value.format(DATETIME_FORMATTER));
        }

        @Override
        public LocalDateTime read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            String datetime = in.nextString();
            try {
                return LocalDateTime.parse(datetime, DATETIME_FORMATTER);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static class SideEnumTypeAdapter extends TypeAdapter<SideEnum> {
        @Override
        public void write(JsonWriter out, SideEnum value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            out.value(value.getCode());
        }

        @Override
        public SideEnum read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            String code = in.nextString().trim();
            return SideEnum.getByCode(code);
        }
    }

    private static class BigDecimalTypeAdapter extends TypeAdapter<BigDecimal> {
        @Override
        public void write(JsonWriter out, BigDecimal value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            out.value(value);
        }

        @Override
        public BigDecimal read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            try {
                if (in.peek() == JsonToken.NUMBER) {
                    return BigDecimal.valueOf(in.nextDouble());
                } else {
                    return new BigDecimal(in.nextString());
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}