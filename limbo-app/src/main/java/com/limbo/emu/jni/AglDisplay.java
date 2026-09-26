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

import android.view.Surface;

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
 */
public final class AglDisplay {
    private AglDisplay() {
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
