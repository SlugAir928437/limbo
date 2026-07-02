package com.max2idea.android.limbo;

import android.app.Activity;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Keep;

import com.max2idea.android.limbo.files.FileInstaller;
import com.max2idea.android.limbo.jni.VMExecutor;
import com.max2idea.android.limbo.main.Config;
import com.max2idea.android.limbo.main.LimboApplication;

import java.io.File;

public class VmUtils {
    public static boolean libLoaded = false;
    private static final String TAG = "VM";

    @Keep
    public static void init() {
        LimboApplication.initialize();
        setupNativeLibs();
    }

    // 必须在主线程调用，否则 QEMU 会崩溃
    public static void setupNativeLibs() {
        if (libLoaded) {
            return;
        }

        try {
            // 1. 基础兼容库
            System.loadLibrary("compat-limbo");
            System.loadLibrary("compat-musl");

            // 2. 依赖库
            System.loadLibrary("glib-2.0");
//             System.loadLibrary("pixman-1");

            // 3. SDL 相关（按正确顺序）
            if (Config.enable_SDL) {
                if (Build.VERSION.SDK_INT >= 26) {
                    System.loadLibrary("compat-SDL2-addons");
                }
                System.loadLibrary("SDL2");
                System.loadLibrary("compat-SDL2-ext");
            }

            // 4. Limbo 核心库
            System.loadLibrary("limbo");

            // 5. QEMU 架构库（根据设备 CPU 智能加载）
            loadQEMULib();

            libLoaded = true;
            Log.i(TAG, "所有原生库加载成功");

        } catch (Throwable e) {
            Log.e(TAG, "原生库加载失败: " + e.getMessage(), e);
            throw new RuntimeException("VM 初始化失败，无法加载原生库", e);
        }
    }

    /**
     * 根据设备架构 智能加载 QEMU 库
     */
    protected static void loadQEMULib() {
        boolean is64Bit = LimboApplication.isHost64Bit();

        // X86 / X86_64
        if (LimboApplication.isHostX86() || LimboApplication.isHostX86_64()) {
            if (is64Bit) {
                System.loadLibrary("qemu-system-x86_64");
            } else {
                System.loadLibrary("qemu-system-i386");
            }
            return;
        }

        // ARM / ARM64
        if (LimboApplication.isHostArm() || LimboApplication.isHostArmv8()) {
            if (is64Bit) {
                System.loadLibrary("qemu-system-aarch64");
            } else {
                System.loadLibrary("qemu-system-arm");
            }
            return;
        }

        throw new UnsupportedOperationException("不支持的设备架构");
    }

    // 启动虚拟机
    public static void start(Activity activity, String[] params) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.e(TAG, "Activity 不可用，无法启动 VM");
            return;
        }

        try {
            // 创建目录
            createMachineDir(LimboApplication.getMachineDir());

            // 安装文件
            FileInstaller.installFiles(activity, false);

            // 启动虚拟机
            VMExecutor.start(params);

        } catch (Exception e) {
            Log.e(TAG, "启动虚拟机失败: " + e.getMessage(), e);
        }
    }

    /**
     * 修复：改为 public static，外部可调用
     */
    public static void createMachineDir(String dir) throws Exception {
        File destDir = new File(dir);
        if (!destDir.exists()) {
            boolean created = destDir.mkdirs();
            if (!created) {
                throw new Exception("无法创建内部机器目录: " + dir);
            }
            Log.i(TAG, "创建机器目录成功: " + dir);
        }
    }
}