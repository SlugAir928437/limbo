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

import android.content.Context;
import android.util.Log;
import android.view.Surface;

import com.limbo.emu.files.FileUtils;
import com.limbo.emu.main.ArchManager;
import com.limbo.emu.main.LimboApplication;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * AGL（Android Graphics Layer）显示通道的入口。
 *
 * <p>QEMU 用 {@code -display agl} 启动时，画面由 AGL 后端直接绘制到 App 的
 * Surface 上，因此需要把 Activity 的 Surface 交给它；触摸、滚轮、键盘事件也
 * 没有 SDL/GTK 那样的事件源，必须由 App 主动推送。这两部分都由 liblimbo.so
 * 提供，它通过 dlsym() 调用已加载的 libqemu-system-*.so 里的 AGL 后端符号。
 *
 * <p>这是 gunyah/gzvm 加速虚拟机唯一可用的显示方式：这两个加速器要求 QEMU
 * 在本进程内以 root 运行（参见 {@link RootUtils#grantRoot()}），而独立 root
 * 子进程拿不到 App 的 Surface。
 *
 * <p>显示通道的接口划分（{@link #setSurface(Surface, float)}、
 * {@link #pointer(float, float, int)} 等）参考自
 * <a href="https://github.com/AnyLaySys/als">AnyLaySys/als</a> 的 AGL.kt，
 * Java 侧实现由本项目编写。
 */
public final class AglDisplay {
    private AglDisplay() {
    }

    private static final String TAG = "AglDisplay";

    /**
     * AGL 后端的入口符号名。
     *
     * <p>发到用户手上的引擎都会被 strip，导出符号表（.dynstr）之外的东西都不剩，
     * 所以只要还能在引擎文件里搜到这个名字，就说明 dlsym("agl_set_window") 拿得
     * 到，AGL 后端确实被编进去了。
     */
    private static final byte[] AGL_SYMBOL =
            "agl_set_window".getBytes(StandardCharsets.US_ASCII);

    /** 探测结果缓存（null = 还没探过）。 */
    private static volatile Boolean backendPresent;

    /**
     * 当前安装包里的那份 QEMU 引擎是否带 AGL 显示后端。
     *
     * <p>QEMU 只在构建时开了 AGL（AGL 补丁 + <code>-Dagl=enabled</code>）才会把
     * <code>agl</code> 收进 qapi 的 DisplayType，也才会编出并导出
     * <code>agl_set_window</code>。引擎里没有这个符号时发 <code>-display agl</code>，
     * QEMU 会把它当成非法显示类型，直接以
     * <pre>libqemu-system-aarch64.so: -display agl: Parameter 'type' does not accept value 'agl'</pre>
     * 退出——画面还没起来，虚拟机已经没了。所以 {@link
     * com.limbo.emu.jni.VMExecutor} 在拼参数前先问这里，缺后端就退回无画面并把
     * 原因告诉用户。
     *
     * <p>结果会缓存；探测本身失败（读不到文件、IO 异常）时返回 true，即保持既有
     * 行为、让 QEMU 自己去报错，而不是把用户挡在 AGL 之外。
     */
    public static boolean isBackendPresent() {
        Boolean cached = backendPresent;
        if (cached != null) {
            return cached;
        }
        boolean present;
        try {
            present = probeBackend();
        } catch (Throwable t) {
            Log.w(TAG, "AGL backend probe failed, assuming it is present", t);
            present = true;
        }
        backendPresent = present;
        return present;
    }

    /** 到引擎文件里找 AGL 的入口符号；找不到（或引擎不在包里）时按“没有”/“别下结论”处理。 */
    private static boolean probeBackend() throws IOException {
        Context context = LimboApplication.getInstance();
        if (context == null) {
            return true;
        }
        String name = ArchManager.libName(LimboApplication.arch);
        // 必须和虚拟机真正加载的那一份是同一个文件：FileUtils#openNativeLib 用的是
        // 与 getNativeLibSearchDir 完全相同的优先级（优先 APK 自己那份，只有 APK 里
        // 是压缩存放时才退回 nativeLibraryDir），否则会探出“说没有、其实有”这类
        // 误导性结论。
        try (InputStream in = FileUtils.openNativeLib(context, name)) {
            if (in == null) {
                // 引擎压根不在包里（该架构不可选）：这里不下结论。
                return true;
            }
            return containsAglSymbol(in);
        }
    }

    /** 流式扫描，找 {@link #AGL_SYMBOL}（模式很短，按首字节过滤后再逐字节比对）。 */
    private static boolean containsAglSymbol(InputStream in) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int carry = 0;
        while (true) {
            int read = in.read(buf, carry, buf.length - carry);
            if (read <= 0) {
                return false;
            }
            int len = carry + read;
            for (int i = 0; i + AGL_SYMBOL.length <= len; i++) {
                if (buf[i] != AGL_SYMBOL[0]) {
                    continue;
                }
                boolean hit = true;
                for (int j = 1; j < AGL_SYMBOL.length; j++) {
                    if (buf[i + j] != AGL_SYMBOL[j]) {
                        hit = false;
                        break;
                    }
                }
                if (hit) {
                    return true;
                }
            }
            // 把块尾的 carry 个字节挪到下一块开头，避免符号跨块时漏判。
            carry = AGL_SYMBOL.length - 1;
            System.arraycopy(buf, len - carry, buf, 0, carry);
        }
    }

    /**
     * 把（或收回）AGL 显示窗口。
     *
     * <p>Surface 通常先于虚拟机启动创建，此时 QEMU 库还没加载，native 层会先把
     * 窗口缓存下来，等 QEMU 初始化完成后自动交出去。
     *
     * @param surface     SurfaceView 的 Surface，传 null 表示窗口已销毁
     * @param refreshRate 屏幕刷新率（Hz），小于等于 0 时由后端自行决定
     */
    public static native void setSurface(Surface surface, float refreshRate);

    /**
     * 上报指针（触摸/鼠标）位置与按键状态。
     *
     * <p>坐标使用 Surface 的像素坐标，由 AGL 后端按实际显示视口换算成客户机
     * 坐标（绝对坐标设备）或相对位移。
     *
     * @param x       Surface 内的 x 坐标
     * @param y       Surface 内的 y 坐标
     * @param buttons 按下的按键掩码（1=左键 2=右键 4=中键）
     */
    public static native void pointer(float x, float y, int buttons);

    /**
     * 上报滚轮滚动，负值表示向上/向左。
     */
    public static native void scroll(float x, float y);

    /**
     * 上报键盘事件。
     *
     * @param scanCode Linux 键码（如 KEY_A=30），非 Android KeyEvent 键值
     * @param down     true 为按下，false 为抬起
     */
    public static native void key(int scanCode, boolean down);
}
