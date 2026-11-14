package com.freshingair.limbo.main;

import android.content.Context;
import android.util.Log;

import com.freshingair.limbo.files.FileUtils;

/**
 * 构建期功能开关。
 *
 * <p>由 jni/Makefile 的 GENERATE_BUILD_FEATURES 写入
 * {@code assets/build-features.properties}，随 APK 打包。资源不存在时按
 * 「全部启用」（EDK2 除外）处理，保持旧构建的行为。
 */
public final class BuildFeatures {
    private static final String TAG = "BuildFeatures";
    private static final String ASSET = "build-features.properties";

    private static boolean sdl = true;
    private static boolean sdlAudio = true;
    private static boolean gtk = true;
    private static boolean virgl = true;
    private static boolean virglVenus = true;
    private static boolean spice = true;
    private static boolean edk2 = false;
    private static boolean loaded = false;

    private BuildFeatures() {
    }

    public static void init(Context context) {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            String text = FileUtils.LoadFile(context, ASSET, false);
            for (String line : text.split("\\r?\\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                boolean value = Boolean.parseBoolean(line.substring(eq + 1).trim());
                switch (key) {
                    case "sdl":
                        sdl = value;
                        break;
                    case "sdl_audio":
                        sdlAudio = value;
                        break;
                    case "gtk":
                        gtk = value;
                        break;
                    case "virgl":
                        virgl = value;
                        break;
                    case "virgl_venus":
                        virglVenus = value;
                        break;
                    case "spice":
                        spice = value;
                        break;
                    case "edk2":
                        edk2 = value;
                        break;
                    default:
                        break;
                }
            }
            Log.d(TAG, "sdl=" + sdl + " sdl_audio=" + sdlAudio + " gtk=" + gtk
                    + " virgl=" + virgl + " venus=" + virglVenus
                    + " spice=" + spice + " edk2=" + edk2);
        } catch (Exception e) {
            Log.w(TAG, "No " + ASSET + ", assuming all features enabled: " + e);
        }
    }

    public static boolean sdl() {
        return sdl;
    }

    public static boolean sdlAudio() {
        return sdlAudio;
    }

    public static boolean gtk() {
        return gtk;
    }

    public static boolean virgl() {
        return virgl;
    }

    public static boolean virglVenus() {
        return virglVenus;
    }

    public static boolean spice() {
        return spice;
    }

    public static boolean edk2() {
        return edk2;
    }
}
