/*
Copyright (C) Max Kastanas 2012

 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 */
package com.limbo.emu.jni;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * JVM entry point for the root child process (launched by VMExecutor through
 * {@code su -c "sh <script>"} + {@code app_process}).  It runs in a process
 * whose uid is 0, loads the same native libs the app loads in-process, and
 * bootstraps the QEMU library exactly like VMExecutor.start() does.
 *
 * Status is reported through {@code <filesDir>/root_vm.status} so the app can
 * distinguish a fast bootstrap failure from a VM that started and later
 * exited.  Arg layout (set by VMExecutor.startRootProcess):
 *   [0] nativeLibDir     [1] filesDir      [2] libFilename (qemu argv[0])
 *   [3] libPath          [4] storageDir    [5] baseDir
 *   [6..] qemu parameters
 */
public class RootVmLauncher {
    private static final String TAG = "RootVmLauncher";

    static final String STATUS_STARTING = "starting";
    static final String STATUS_PREFIX_STOPPED = "stopped ";
    static final String STATUS_PREFIX_ERROR = "error ";
    static final String STATUS_FILENAME = "root_vm.status";

    /**
     * 启动阶段标记：写在 {@code starting} 之后覆盖同一个状态文件。
     *
     * <p>父进程在异常退出时会把状态文件的内容原样带进报错，于是"死在加载哪个
     * native 库"和"死在 QEMU 里"就能区分开。这一点很关键：子进程 uid 0，既拿不到
     * tombstone，它的 logcat 也对 App 不可见（App 只能读自己 uid 的日志），状态
     * 文件是唯一一条不依赖 uid 的线索。
     */
    static final String STATUS_STAGE_LIB = "starting: load ";
    static final String STATUS_STAGE_VM  = "starting: qemu";

    // Native counterpart: Java_com_android_limbo_jni_RootVmLauncher_startVm
    // (static), which reuses the shared start_qemu() bootstrap.
    private static native String startVm(String storageDir, String baseDir,
                                         String libFilename, String libPath,
                                         String[] params);

    public static void main(String[] args) {
        if (args.length < 6) {
            Log.e(TAG, "Too few args: " + args.length);
            System.exit(2);
            return;
        }
        String nativeLibDir = args[0];
        String filesDir = args[1];
        String libFilename = args[2];
        String libPath = args[3];
        String storageDir = args[4];
        String baseDir = args[5];
        String[] params = new String[args.length - 6];
        System.arraycopy(args, 6, params, 0, params.length);

        File statusFile = new File(filesDir, STATUS_FILENAME);
        writeStatus(statusFile, STATUS_STARTING);

        try {
            // Mirror LimboActivity.setupNativeLibs() load order.  Missing
            // optional libs are tolerated; the linker resolves the rest of
            // the qemu dependencies through LD_LIBRARY_PATH (exported by the
            // launching script).  每个库加载前先落一次状态，见 STATUS_STAGE_LIB。
            loadOrIgnore(statusFile, nativeLibDir, "libcompat-limbo.so", false);
            loadOrIgnore(statusFile, nativeLibDir, "libcompat-musl.so", false);
            loadOrIgnore(statusFile, nativeLibDir, "libglib-2.0.so", false);
            loadOrIgnore(statusFile, nativeLibDir, "libSDL2.so", true);
            loadOrIgnore(statusFile, nativeLibDir, "libcompat-SDL2-addons.so", true);
            loadOrIgnore(statusFile, nativeLibDir, "libcompat-SDL2-ext.so", true);
            loadOrIgnore(statusFile, nativeLibDir, "liblimbo.so", false);

            writeStatus(statusFile, STATUS_STAGE_VM);
            String res = startVm(storageDir, baseDir, libFilename, libPath, params);
            Log.i(TAG, "VM exited: " + res);
            writeStatus(statusFile, STATUS_PREFIX_STOPPED + res);
            System.exit(0);
        } catch (Throwable t) {
            Log.e(TAG, "Root VM bootstrap failed", t);
            writeStatus(statusFile, STATUS_PREFIX_ERROR + t);
            System.exit(1);
        }
    }

    private static void loadOrIgnore(File statusFile, String nativeLibDir, String lib,
                                     boolean optional) {
        // 先记下"正要加载哪个库"：这一步崩掉时状态文件就停在这里，
        // 父进程报错里会原样显示出来。
        writeStatus(statusFile, STATUS_STAGE_LIB + lib);

        // nativeLibDir 可能是普通目录（安装时解压出 .so），也可能是 APK 内路径
        // （<apk>!/lib/<abi>，关闭安装时解压的情形）。后者无法用 File 判断存在性，
        // 直接交给链接器从 APK 内加载。
        String path;
        if (nativeLibDir != null && nativeLibDir.contains("!")) {
            path = nativeLibDir + "/" + lib;
        } else {
            File f = new File(nativeLibDir, lib);
            if (!f.exists()) {
                if (!optional) {
                    throw new UnsatisfiedLinkError("Missing " + lib + " in " + nativeLibDir);
                }
                Log.w(TAG, "Optional lib not present: " + lib);
                return;
            }
            path = f.getAbsolutePath();
        }
        try {
            System.load(path);
        } catch (UnsatisfiedLinkError e) {
            // APK 内路径（<apk>!/lib/<abi>）没法用 File 探测存在性，只能在
            // System.load() 时才知道有没有；可选库缺失不应该让整个 root 子进程
            // 启动失败，必需库则照旧抛出（由 main() 写入 error 状态）。
            if (!optional) {
                throw e;
            }
            Log.w(TAG, "Optional lib failed to load: " + path, e);
        }
    }

    private static void writeStatus(File statusFile, String status) {
        try {
            try (FileOutputStream out = new FileOutputStream(statusFile, false)) {
                out.write(status.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.e(TAG, "Could not write status file " + statusFile, t);
        }
    }
}
