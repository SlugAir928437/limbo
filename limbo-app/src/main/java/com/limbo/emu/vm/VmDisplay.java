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

import androidx.annotation.Nullable;

import com.limbo.emu.jni.AglDisplay;
import com.limbo.emu.main.Config;

/**
 * AGL 显示/输入的统一入口。
 *
 * <p>默认（{@link Config#vmProcessIsolated} 为 false）直接调用 {@link AglDisplay}
 * 的 JNI 方法，与改造前完全一致；当原生 VM 被隔离到独立进程时，原生库不在本进程，
 * 改为通过 {@link IVmExecutorService} 转发给服务端。
 *
 * <p>Surface 本身是可跨进程传递的（{@code android.view.Surface} 实现了
 * {@code Parcelable}），因此"App 进程创建 SurfaceView、VM 进程渲染"是可行的。
 */
public final class VmDisplay {
    private VmDisplay() {
    }

    @Nullable
    private static AidlVmExecutor clientForForwarding() {
        if (!Config.enableAidlVm || !Config.vmProcessIsolated) {
            return null;
        }
        return AidlVmExecutor.current();
    }

    public static void setSurface(Surface surface, float refreshRate) {
        AidlVmExecutor client = clientForForwarding();
        if (client != null && client.setSurface(surface, refreshRate)) {
            return;
        }
        AglDisplay.setSurface(surface, refreshRate);
    }

    public static void pointer(float x, float y, int buttons) {
        AidlVmExecutor client = clientForForwarding();
        if (client != null && client.aglPointer(x, y, buttons)) {
            return;
        }
        AglDisplay.pointer(x, y, buttons);
    }

    public static void scroll(float x, float y) {
        AidlVmExecutor client = clientForForwarding();
        if (client != null && client.aglScroll(x, y)) {
            return;
        }
        AglDisplay.scroll(x, y);
    }

    public static void key(int scanCode, boolean down) {
        AidlVmExecutor client = clientForForwarding();
        if (client != null && client.aglKey(scanCode, down)) {
            return;
        }
        AglDisplay.key(scanCode, down);
    }
}
