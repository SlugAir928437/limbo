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

/**
 * 原生层（JNI）向 App 客户端回传事件的单向通道。
 *
 * <p>VM 执行体运行在 {@link IVmExecutorService} 这一侧（Binder 服务进程），
 * 分辨率变化、VM 启停、日志等"由下往上"的通知都通过本接口回调给客户端。
 *
 * <p>声明为 {@code oneway}：服务端调用不会被客户端阻塞，避免原生渲染线程
 * 因为客户端主线程繁忙而抖动。
 */
oneway interface IVmExecutorCallback {

    /** 虚拟机已开始运行。 */
    void onVmStarted();

    /**
     * 虚拟机已退出。
     *
     * @param reason 退出原因（正常关机时为 "VM shutdown"）
     */
    void onVmStopped(String reason);

    /**
     * 客户机分辨率发生变化。
     *
     * @param width  客户机画面宽度
     * @param height 客户机画面高度
     */
    void onResolutionChanged(int width, int height);

    /** 原生层日志。 */
    void onLog(String line);
}
