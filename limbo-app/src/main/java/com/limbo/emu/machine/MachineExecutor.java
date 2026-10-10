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
package com.limbo.emu.machine;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Our emulation abstract bridge. It can be extended to implement bridges with other native
 * emulators that support SDL.
 */
public abstract class MachineExecutor {
    private static String TAG = "MachineExecutor";

    private final MachineController machineController;

    /**
     * 分辨率变化监听器。
     *
     * <p>存在的意义：当执行体被搬到 AIDL 服务端（{@code VmExecutorService}）之后，
     * {@link MachineController} 里"上报者必须等于当前执行器"的校验会让服务端那个
     * 原生执行器的回调被丢弃。服务端改为直接在本接口上挂监听，再通过
     * {@code IVmExecutorCallback} 转发给客户端。
     */
    public interface OnResolutionChangedListener {
        void onResolutionChanged(int vm_width, int vm_height);
    }

    private final Set<OnResolutionChangedListener> resolutionChangedListeners =
            new CopyOnWriteArraySet<>();

    public MachineExecutor(MachineController machineController) {
        this.machineController = machineController;
    }

    /** 注册分辨率变化监听器。 */
    public void addOnResolutionChangedListener(OnResolutionChangedListener listener) {
        if (listener != null) {
            resolutionChangedListeners.add(listener);
        }
    }

    /** 注销分辨率变化监听器。 */
    public void removeOnResolutionChangedListener(OnResolutionChangedListener listener) {
        resolutionChangedListeners.remove(listener);
    }

    protected Machine getMachine() {
        return machineController.getMachine();
    }

    protected void onResolutionChanged(int vm_width, int vm_height) {
        machineController.onVMResolutionChanged(this, vm_width, vm_height);
        for (OnResolutionChangedListener listener : resolutionChangedListeners) {
            listener.onResolutionChanged(vm_width, vm_height);
        }
    }

    abstract public void startService();

    // TODO: create int success code instead of string
    abstract public String start();

    public abstract void stopvm(final int restart);


    public abstract int getSdlRefreshRate(boolean idle);

    public abstract void setSdlRefreshRate(int refreshMs, boolean idle);

    public abstract void sendMouseEvent(int button, int action, int relative, float x, float y);

    public abstract String saveVM();

    public abstract void continueVM();

    public abstract MachineController.MachineStatus getSaveVMStatus();

    public abstract void enableAaudio(int value);

    public abstract boolean changeRemovableDevice(MachineProperty drive, String diskValue);

    public abstract String getDeviceName(MachineProperty driveProperty);

    public abstract void updateDisplay(int width, int height, int orientation);

    public abstract void setFullscreen();
}
