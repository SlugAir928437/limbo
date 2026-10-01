package com.limbo.emu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * local.properties 中除 sdk.dir 以外的所有键值对，会被 Gradle 注入到
 * {@link BuildConfig}，字段名规则：非 [A-Za-z0-9_] 的字符替换为下划线。
 *
 * 例如：
 *   local.properties:  bugly.appId=xxxxxxxx
 *   BuildConfig:       public static final String bugly_appId = "xxxxxxxx";
 *
 * 本类提供唯一通用入口 {@link #get(String)}：传入 local.properties 里的
 * 原始 key（如 "bugly.appId"）或合法化后的字段名（如 "bugly_appId"）均可，
 * 内部自动完成字段名合法化与反射读取。
 *
 * 注意：BuildConfig 的 String 字段是编译期常量，可能被内联，反射不一定
 * 能枚举到全部字段；因此 {@link #all()} 仅用于调试，正式逻辑请走 {@link #get}。
 */
public final class local_properties {

    private local_properties() {
    }

    // ---------------------------------------------------------------------
    // 唯一通用入口
    // ---------------------------------------------------------------------

    /**
     * 通用获取入口。
     *
     * 传入 local.properties 的原始 key（"bugly.appId"）或合法化后的字段名
     * （"bugly_appId"）都可以，内部会统一合法化后再查 BuildConfig。
     *
     * @param key local.properties 的键，或合法化后的 BuildConfig 字段名
     * @return 对应的字符串值；不存在或非 String 时返回 null
     */
    @Nullable
    public static String get(@Nullable String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String fieldName = normalize(key);
        try {
            Field field = BuildConfig.class.getField(fieldName);
            if (field.getType() != String.class) {
                return null;
            }
            Object value = field.get(null);
            return value instanceof String ? (String) value : null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }

    /**
     * 通用获取入口，缺失时返回默认值。
     */
    @NonNull
    public static String get(@Nullable String key, @NonNull String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * 通用获取入口，缺失或为空串时返回默认值。
     */
    @NonNull
    public static String getNonEmpty(@Nullable String key, @NonNull String defaultValue) {
        String value = get(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    /**
     * 对应变量是否存在且非空。
     */
    public static boolean has(@Nullable String key) {
        String value = get(key);
        return value != null && !value.isEmpty();
    }

    // ---------------------------------------------------------------------
    // 调试辅助
    // ---------------------------------------------------------------------

    /**
     * 反射枚举 BuildConfig 中所有 public static final String 字段。
     *
     * 仅用于调试：开启常量内联后部分字段可能不产生真实字段，结果不一定完整。
     */
    @NonNull
    public static Map<String, String> all() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Field field : BuildConfig.class.getFields()) {
            if (field.getType() != String.class) {
                continue;
            }
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                Object value = field.get(null);
                if (value instanceof String) {
                    result.put(field.getName(), (String) value);
                }
            } catch (IllegalAccessException ignored) {
                // 忽略无法访问的字段
            }
        }
        return Collections.unmodifiableMap(result);
    }

    // ---------------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------------

    /**
     * 把 local.properties 的 key 合法化为 BuildConfig 字段名：
     * 非 [A-Za-z0-9_] 的字符替换为下划线。
     * 已经是合法字段名的输入会被原样返回。
     */
    @NonNull
    private static String normalize(@NonNull String key) {
        return key.replaceAll("[^A-Za-z0-9_]", "_");
    }
}