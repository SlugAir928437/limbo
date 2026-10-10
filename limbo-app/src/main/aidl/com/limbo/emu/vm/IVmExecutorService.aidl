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
package com.limbo.emu.vm;

import android.view.Surface;
import com.limbo.emu.vm.IVmExecutorCallback;

/**
 * App（客户端）与 JNI 原生执行体之间的 AIDL 契约。
 *
 * <p>方法集合刻意与 {@code com.limbo.emu.machine.MachineExecutor} 一一对应，
 * 这样 {@link com.limbo.emu.jni.VMExecutor}（直接在进程内调用 JNI 的实现）
 * 可以整体搬到服务端，客户端只需持有一个通过 Binder 转发的代理即可。
 *
 * <p>约定：AIDL 不支持枚举，所以凡是枚举入参/返回值一律用 {@code int} 传递：
 * <ul>
 *   <li>驱动器属性用 {@code MachineProperty.ordinal()}；</li>
 *   <li>保存状态用 {@code MachineController.MachineStatus.ordinal()}；</li>
 *   <li>布尔值用 0/1。</li>
 * </ul>
 */
interface IVmExecutorService {

    // ------------------------------------------------------------------
    // 会话管理
    // ------------------------------------------------------------------

    /** 注册/注销事件回调（可注册多个，断开时自动移除）。 */
    void registerCallback(IVmExecutorCallback callback);
    void unregisterCallback(IVmExecutorCallback callback);

    /**
     * 指定本次会话要运行的虚拟机。
     *
     * <p>当服务与客户端不在同一进程时，服务端进程没有界面的 {@code Machine}
     * 上下文，必须先通过本方法把机器名同步过去，之后才能调用 {@link #start()}。
     *
     * @param machineName 机器名（{@code Machine} 的唯一标识）
     */
    void setMachine(String machineName);

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 启动虚拟机并阻塞直到其退出（与进程内 {@code MachineExecutor.start()} 语义一致）。
     *
     * @return 正常关机返回 "VM shutdown"，否则返回错误描述
     */
    String start();

    /**
     * 停止/重启虚拟机。
     *
     * @param restart 非 0 表示重启（走 QMP reset），0 表示直接退出
     */
    void stopVm(int restart);

    // ------------------------------------------------------------------
    // 暂停 / 恢复 / 状态
    // ------------------------------------------------------------------

    /** 保存（暂停）虚拟机状态，出错时返回错误描述。 */
    String saveVm();

    /** 继续被暂停的虚拟机。 */
    void continueVm();

    /** 查询保存状态，返回 {@code MachineController.MachineStatus.ordinal()}。 */
    int getSaveVmStatus();

    // ------------------------------------------------------------------
    // 显示
    // ------------------------------------------------------------------

    /**
     * 把 Android Surface 交给原生显示后端（AGL/GTK/…）。
     *
     * @param surface     SurfaceView 的 Surface，null 表示窗口已销毁
     * @param refreshRate 屏幕刷新率（Hz），<=0 时由后端自行决定
     */
    void setSurface(in Surface surface, float refreshRate);

    /** 屏幕尺寸/方向变化。 */
    void updateDisplay(int width, int height, int orientation);

    /** 全屏并立刻刷新画面。 */
    void setFullscreen();

    /** 请求立刻重绘一帧。 */
    void refreshScreen(int value);

    /** 设置 SDL 缩放模式（见 Config.SDL_SCALE_*）。 */
    void setSdlScaleMode(int mode);

    /** AGL 后端：上报指针位置与按键掩码。 */
    void aglPointer(float x, float y, int buttons);

    /** AGL 后端：上报滚轮滚动。 */
    void aglScroll(float x, float y);

    /** AGL 后端：上报键盘事件（scanCode 为 Linux 键码）。 */
    void aglKey(int scanCode, boolean down);

    // ------------------------------------------------------------------
    // 刷新率
    // ------------------------------------------------------------------

    /** @param idle 1 表示空闲刷新率，0 表示默认刷新率 */
    void setSdlRefreshRate(int refreshMs, int idle);

    /** @param idle 1 表示空闲刷新率，0 表示默认刷新率 */
    int getSdlRefreshRate(int idle);

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /** 发送鼠标事件。 */
    void sendMouseEvent(int button, int action, int relative, int x, int y);

    /** 设置绝对指针设备的边界。 */
    void setMouseBounds(int xmin, int xmax, int ymin, int ymax);

    // ------------------------------------------------------------------
    // 音频
    // ------------------------------------------------------------------

    /** 启用/禁用 AAudio。 */
    void enableAaudio(int value);

    // ------------------------------------------------------------------
    // 可移动设备
    // ------------------------------------------------------------------

    /**
     * 更换可移动设备（光驱/软驱/SD）。
     *
     * @param driveOrdinal {@code MachineProperty.ordinal()}
     * @return 是否成功
     */
    boolean changeRemovableDevice(int driveOrdinal, String value);

    /**
     * 获取设备在 QEMU/QMP 中的名字。
     *
     * @param driveOrdinal {@code MachineProperty.ordinal()}
     */
    String getDeviceName(int driveOrdinal);
}
