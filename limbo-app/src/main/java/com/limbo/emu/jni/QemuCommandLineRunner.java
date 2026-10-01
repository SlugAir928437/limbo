package com.limbo.emu.jni;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limbo.emu.MainActivity;
import com.limbo.emu.files.FileUtils;
import com.limbo.emu.main.Config;
import com.limbo.emu.main.LimboApplication;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 测试用：把一条 QEMU 命令行交给 {@link VMExecutor} 的原生引导流程执行。
 *
 * <p>与 {@code MachineController.startVm()} 的日常路径不同，这里<b>不做任何参数拼装</b>：
 * 传入的参数数组会被原样交给原生层（{@code loadLib + qemu_init + qemu_main_loop}），
 * 既不会追加 {@code -display}/{@code -monitor}/{@code -serial} 之类的选项，也不会改动
 * 已经写好的参数。
 *
 * <p>使用顺序：
 * <ol>
 *     <li>主线程调用 {@link #loadNativeLibs()}（与 {@code LimboActivity.setupNativeLibs()}
 *         的顺序一致，且必须在主线程加载，否则 QEMU 之后启动会崩溃）；</li>
 *     <li>后台线程调用 {@link #start(String[])}，该方法会阻塞到 QEMU 退出；</li>
 *     <li>需要提前结束时可调用 {@link #stop()} 请求关机。</li>
 * </ol>
 *
 * <p>注意：QEMU 自身的 stdout/stderr 属于本进程输出，会进入 logcat，
 * 本类无法把它们收集成字符串返回。
 */
public final class QemuCommandLineRunner {

    private static final String TAG = "QemuCommandLineRunner";

    /** 与 {@code libqemu-system-<target>.so} 对应的架构名。 */
    private static final String QEMU_LIB_PREFIX = "qemu-system-";

    private static boolean nativeLibsLoaded;

    private QemuCommandLineRunner() {
    }

    /**
     * 主线程调用：按 {@code LimboActivity.setupNativeLibs()} 的顺序加载原生库，
     * 并探测/加载当前可用的 QEMU 架构库（与 {@code MainActivity.onCreate} 一致）。
     */
    public static synchronized void loadNativeLibs() {
        if (nativeLibsLoaded) {
            return;
        }

        // 1) 探测并加载 QEMU 架构库（同时设置 LimboApplication.arch）
        LimboApplication.arch = MainActivity.checkQEMULib();

        // 2) 依赖库，顺序与 LimboActivity.setupNativeLibs() 保持一致
        System.loadLibrary("compat-limbo");
        System.loadLibrary("compat-musl");
        System.loadLibrary("glib-2.0");
        if (Config.enable_SDL) {
            if (Build.VERSION.SDK_INT >= 26) {
                System.loadLibrary("compat-SDL2-addons");
            }
            System.loadLibrary("SDL2");
        }
        System.loadLibrary("compat-SDL2-ext");

        // 3) JNI 桥本身（VMExecutor 的原生实现就在 liblimbo.so 里）
        System.loadLibrary("limbo");

        nativeLibsLoaded = true;
        Log.d(TAG, "Native libs loaded, arch=" + LimboApplication.arch
                + ", qemuLib=" + getLoadedQemuLibFilename());
    }

    /**
     * 把一条命令行拆成参数数组：
     * 支持单引号、双引号以及反斜杠转义，除解析引号外不做任何改动，
     * <b>不会追加任何参数</b>。
     */
    @NonNull
    public static String[] parseCommandLine(@NonNull String commandLine) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        char quote = 0;

        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);

            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < commandLine.length()) {
                    // 双引号内的转义
                    current.append(commandLine.charAt(++i));
                } else {
                    current.append(c);
                }
                continue;
            }

            if (c == '\'' || c == '"') {
                quote = c;
                inToken = true;
            } else if (c == '\\' && i + 1 < commandLine.length()) {
                current.append(commandLine.charAt(++i));
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }

        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens.toArray(new String[0]);
    }

    /**
     * 解析需要 dlopen 的 QEMU 库文件名。
     *
     * <p>优先按命令行的第一个参数（QEMU 程序名，如 {@code qemu-system-x86_64}）推断，
     * 推断出的库在本应用的 native 库目录中不存在时，退回当前已经加载的架构库。
     */
    @NonNull
    public static String resolveLibFilename(@NonNull String[] params) {
        String target = qemuTargetFromProgramName(params.length > 0 ? params[0] : null);
        Context context = LimboApplication.getInstance();
        if (target != null && context != null) {
            String candidate = "lib" + QEMU_LIB_PREFIX + target + ".so";
            File libFile = new File(FileUtils.getNativeLibDir(context), candidate);
            if (libFile.exists()) {
                return candidate;
            }
            Log.w(TAG, "命令行指定的 QEMU 库不存在，退回已加载的架构库: " + candidate);
        }
        String loaded = MainActivity.getLoadedLibName();
        if (loaded == null) {
            throw new IllegalStateException("QEMU 库尚未加载，请先调用 loadNativeLibs()");
        }
        return "lib" + loaded + ".so";
    }

    /**
     * 阻塞执行：把参数原样交给原生 QEMU 引导流程，直到 QEMU 退出后返回原生结果。
     *
     * <p>必须在非主线程调用。
     */
    @Nullable
    public static String start(@NonNull String[] params) {
        VMExecutor executor = VMExecutor.obtain();
        if (executor == null) {
            throw new IllegalStateException("无法获取 VMExecutor 实例");
        }
        String libFilename = resolveLibFilename(params);
        Log.d(TAG, "启动 QEMU: lib=" + libFilename + ", argv=" + Arrays.toString(params));
        return executor.startRaw(params, libFilename);
    }

    /** 请求终止当前正在运行的 QEMU（等价于界面上的「停止」，即请求关机）。 */
    @Nullable
    public static String stop() {
        VMExecutor executor = VMExecutor.obtain();
        if (executor == null) {
            return null;
        }
        Log.d(TAG, "请求停止 QEMU");
        return executor.stopRaw(0);
    }

    /** 当前已加载的 QEMU 库文件名，形如 {@code libqemu-system-x86_64.so}。 */
    @Nullable
    private static String getLoadedQemuLibFilename() {
        String loaded = MainActivity.getLoadedLibName();
        return loaded == null ? null : "lib" + loaded + ".so";
    }

    /**
     * 从 QEMU 程序名中取出架构名：
     * {@code qemu-system-x86_64} / {@code libqemu-system-x86_64.so} /
     * {@code /data/local/tmp/qemu-system-aarch64} 都会得到 {@code x86_64} / {@code aarch64}。
     */
    @Nullable
    private static String qemuTargetFromProgramName(@Nullable String programName) {
        if (programName == null || programName.isEmpty() || programName.startsWith("-")) {
            return null;
        }
        String name = programName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.startsWith("lib")) {
            name = name.substring(3);
        }
        if (name.endsWith(".so")) {
            name = name.substring(0, name.length() - 3);
        }
        if (!name.startsWith(QEMU_LIB_PREFIX)) {
            return null;
        }
        String target = name.substring(QEMU_LIB_PREFIX.length());
        return target.isEmpty() ? null : target;
    }
}
