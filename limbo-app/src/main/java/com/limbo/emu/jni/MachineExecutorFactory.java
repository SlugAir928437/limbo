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

import android.app.Application;
import android.os.Build;

import androidx.annotation.Nullable;

import com.limbo.emu.main.Config;
import com.limbo.emu.machine.MachineController;
import com.limbo.emu.machine.MachineExecutor;
import com.limbo.emu.vm.AidlVmExecutor;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Objects;

/**
 * 执行器工厂。
 *
 * <p>现在有两条路径：
 * <ul>
 *   <li><b>客户端（App 进程）</b>：返回 {@link AidlVmExecutor}，所有对 JNI 的调用
 *       都先经过 AIDL(Binder) 转发给 {@code VmExecutorService}；</li>
 *   <li><b>服务端（VM 进程 / 独立进程）</b>：返回 {@link VMExecutor}，直接在本进程
 *       调用 JNI，是 AIDL 服务的实现后端。</li>
 * </ul>
 */
public class MachineExecutorFactory {
    private static final String TAG = "MachineExecutorFactory";

    /** 独立 VM 进程名后缀，与 AndroidManifest 里 {@code android:process=":vm"} 对应。 */
    public static final String VM_PROCESS_SUFFIX = ":vm";

    public static MachineExecutor createMachineExecutor(MachineController machineController, MachineExecutorType type) {
        if (Objects.requireNonNull(type) != MachineExecutorType.QEMU) {
            return null;
        }
        // 位于 VM 服务进程时不能再返回 AIDL 代理，否则会自己绑定自己形成递归。
        if (Config.enableAidlVm && !isVmProcess()) {
            return new AidlVmExecutor(machineController);
        }
        return new VMExecutor(machineController);
    }

    /**
     * 强制创建进程内的原生执行器（不受 {@link Config#enableAidlVm} 影响）。
     *
     * <p>供 {@code VmExecutorService} 使用：它就是 AIDL 服务端真正驱动 JNI 的实现。
     */
    public static MachineExecutor createLocalMachineExecutor(MachineController machineController) {
        return new VMExecutor(machineController);
    }

    /** 当前进程是否是隔离出来的 VM 服务进程（{@code :vm}）。 */
    public static boolean isVmProcess() {
        String processName = currentProcessName();
        return processName != null && processName.endsWith(VM_PROCESS_SUFFIX);
    }

    @Nullable
    private static String currentProcessName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return Application.getProcessName();
            } catch (Throwable ignored) {
                // 退回到读取 /proc/self/cmdline
            }
        }
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
            StringBuilder builder = new StringBuilder();
            int c;
            while ((c = reader.read()) > 0) {
                builder.append((char) c);
            }
            return builder.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public enum MachineExecutorType {
        QEMU
    }
}
