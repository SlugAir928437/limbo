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
            // launching script).
            loadOrIgnore(nativeLibDir, "libcompat-limbo.so", false);
            loadOrIgnore(nativeLibDir, "libcompat-musl.so", false);
            loadOrIgnore(nativeLibDir, "libglib-2.0.so", false);
            loadOrIgnore(nativeLibDir, "libSDL2.so", true);
            loadOrIgnore(nativeLibDir, "libcompat-SDL2-addons.so", true);
            loadOrIgnore(nativeLibDir, "libcompat-SDL2-ext.so", true);
            loadOrIgnore(nativeLibDir, "liblimbo.so", false);

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

    private static void loadOrIgnore(String nativeLibDir, String lib, boolean optional) {
        File f = new File(nativeLibDir, lib);
        if (!f.exists()) {
            if (!optional) {
                throw new UnsatisfiedLinkError("Missing " + lib + " in " + nativeLibDir);
            }
            Log.w(TAG, "Optional lib not present: " + lib);
            return;
        }
        System.load(f.getAbsolutePath());
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
