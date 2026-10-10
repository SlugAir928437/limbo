/*
 Copyright (C) Max Kastanas 2012

 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 */
package com.limbo.emu.jni;

import android.os.Environment;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 内部储存（emulated 卷）的可用性检查、修复，以及给 root 子进程用的路径改写。
 *
 * <p>背景：/storage/emulated/&lt;user&gt; 是 FUSE 挂载，由 MediaProvider 里的守护进程
 * 提供服务。这个卷一旦被 vold 摘掉（media 守护进程被杀、FUSE 会话异常结束、储存卷被
 * 弹出等），AOSP 会把外部储存根目录解析成占位路径 {@code /dev/null}，
 * 于是 {@code getExternalCacheDir()} 会给出
 * {@code /dev/null/Android/data/<pkg>/cache} 这种路径
 * （vold 侧同步报 "Failed to find mounted volume for /dev/null/Android/data/..."）。
 * 后果是磁盘镜像打不开、日志写不进去，App 甚至会在 Application 初始化阶段直接闪退
 * —— 也就是"内部储存被取消挂载"之后看到的一连串现象。
 *
 * <p>所以这里提供两件事：
 * <ul>
 *   <li>{@link #isMounted()} / {@link #repair()}：判断卷是否真的可用，并用 root
 *       通过 {@code sm mount} 把它挂回来，省掉"储存掉了只能重启手机"；</li>
 *   <li>{@link #remapForRoot(String[])}：让 root 子进程绕开 FUSE，直接走底层目录
 *       {@code /data/media/<user>}，从源头上减少"root 进程读写 /sdcard"这一类故障。</li>
 * </ul>
 */
public final class ExternalStorage {
    private static final String TAG = "ExternalStorage";

    /** 卷没挂载时 AOSP 使用的占位根目录。 */
    private static final String PLACEHOLDER_ROOT = "/dev/null";
    /** FUSE 视图路径前缀：/storage/emulated/&lt;userId&gt;。 */
    private static final String EMULATED_PREFIX = "/storage/emulated/";
    /** FUSE 视图背后的真实目录，和视图是同一份文件。 */
    private static final String RAW_PREFIX = "/data/media/";
    /** su 命令超时时间（毫秒）。 */
    private static final long SU_TIMEOUT_MS = 8000;
    /** su 命令轮询间隔（毫秒）。 */
    private static final long SU_POLL_MS = 100;

    private ExternalStorage() {
    }

    /**
     * 外部储存根目录。
     *
     * @return 可用的根目录；被卸载（/dev/null 占位）或取不到时返回 null
     */
    @Nullable public static File root() {
        try {
            File dir = Environment.getExternalStorageDirectory();
            if (dir == null) {
                return null;
            }
            String path = dir.getAbsolutePath();
            if (PLACEHOLDER_ROOT.equals(path) || path.startsWith(PLACEHOLDER_ROOT + "/")) {
                return null;
            }
            return dir;
        } catch (Throwable t) {
            Log.w(TAG, "Could not resolve external storage root", t);
            return null;
        }
    }

    /**
     * 外部储存当前是否真的可用。
     *
     * <p>不只依赖 {@link Environment#getExternalStorageState()}：卷被摘掉后状态确实会
     * 变成 unmounted/removed，但日志里出现过"状态看着正常、路径却已经是
     * {@code /dev/null/...}"的情况，因此两个条件都满足才算可用。
     */
    public static boolean isMounted() {
        try {
            if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        return root() != null;
    }

    /**
     * 尝试把被摘掉的内部储存卷重新挂回来（需要 root）。
     *
     * <p>用 {@code sm}（StorageManagerService 的 shell 客户端）：先直接 mount；
     * 如果卷处于"还挂着但已经没人服务"的坏状态，先 unmount 再 mount。
     * 这两条都不做任何破坏性操作：卷本来就没挂载时，unmount 只是返回一个错误。
     *
     * @return 修复后外部储存是否可用
     */
    public static boolean repair() {
        if (isMounted()) {
            return true;
        }
        if (!RootUtils.isRoot() && !RootUtils.hasSu()) {
            Log.w(TAG, "External storage is not mounted and su is unavailable");
            return false;
        }
        final String volId = "emulated;" + userId();
        Log.w(TAG, "External storage is not mounted, trying to mount " + volId);
        execSu("sm mount '" + volId + "'");
        if (isMounted()) {
            return true;
        }
        // 坏状态兜底：先摘干净再挂，否则 mount 会直接失败
        execSu("sm unmount '" + volId + "'; sm mount '" + volId + "'");
        boolean mounted = isMounted();
        if (!mounted) {
            Log.e(TAG, "Could not remount external storage " + volId);
        }
        return mounted;
    }

    /** 当前用户 id（就是卷 id "emulated;&lt;userId&gt;" 里的那一段）。 */
    private static int userId() {
        try {
            // 等价于 UserHandle.myUserId()，但不依赖 API 版本
            return Process.myUid() / 100000;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 把参数里所有 FUSE 视图路径（{@code /storage/emulated/<user>}）改写成它背后的
     * 真实目录（{@code /data/media/<user>}），供 root 子进程使用。
     *
     * <p>为什么 root 要绕开 FUSE：FUSE 的守护进程在 MediaProvider 里，root 的每一次
     * 读写都要经过 scoped storage 判定；既慢（磁盘镜像在 FUSE 上性能损失明显），也是
     * "root 进程访问 /sdcard 之后系统储存出问题"这一类故障的来源，所以让 root 直接读写
     * 底层目录更稳。底层目录和 FUSE 视图是同一份文件，文件管理器里照旧能看到。
     *
     * <p>只在底层目录确实存在时才替换，避免把本来能用的路径改坏。
     *
     * @param params QEMU 参数
     * @return 改写后的参数（没有需要改的项时原样返回）
     */
    @NonNull public static String[] remapForRoot(@NonNull String[] params) {
        String[] out = null;
        for (int i = 0; i < params.length; i++) {
            String p = params[i];
            if (p == null) {
                continue;
            }
            String mapped = remapPathForRoot(p);
            if (!mapped.equals(p)) {
                if (out == null) {
                    out = params.clone();
                }
                out[i] = mapped;
            }
        }
        return out == null ? params : out;
    }

    /**
     * 单个参数里的路径改写，规则见 {@link #remapForRoot(String[])}。
     * 参数可能是纯路径（-kernel / -bios），也可能是
     * {@code index=0,if=virtio,file=/storage/emulated/0/xx.qcow2} 这种片段，
     * 因此按子串替换而不是整串判断。
     */
    @NonNull public static String remapPathForRoot(@NonNull String value) {
        int idx = value.indexOf(EMULATED_PREFIX);
        if (idx < 0) {
            return value;
        }
        int start = idx + EMULATED_PREFIX.length();
        int end = start;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (end == start) {
            // 形如 /storage/emulated/legacy：不认，保持原样
            return value;
        }
        String raw = RAW_PREFIX + value.substring(start, end);
        if (!new File(raw).exists()) {
            return value;
        }
        return value.substring(0, idx) + raw + value.substring(end);
    }

    /** 以 root 身份执行一条 shell 命令（忽略输出，只等它结束）。 */
    private static void execSu(@NonNull String cmd) {
        // 注意用全限定名：本文件里 Process 指的是 android.os.Process（取 uid 用）
        java.lang.Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            final java.lang.Process proc = p;
            // 不读干输出的话，su 的输出缓冲写满会把命令卡死
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        proc.getInputStream(), StandardCharsets.UTF_8))) {
                    while (r.readLine() != null) {
                        // su 的输出没有用处
                    }
                } catch (Throwable ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();

            long deadline = System.currentTimeMillis() + SU_TIMEOUT_MS;
            // minSdk 24：不能用 Process.waitFor(timeout, unit)
            while (System.currentTimeMillis() < deadline && isAlive(p)) {
                try {
                    Thread.sleep(SU_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "su command failed: " + cmd, t);
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    /** 进程是否还活着。 */
    private static boolean isAlive(@NonNull java.lang.Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }
}
