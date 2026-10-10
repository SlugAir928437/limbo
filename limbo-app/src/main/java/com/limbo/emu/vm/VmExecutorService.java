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

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;

import com.limbo.emu.jni.AglDisplay;
import com.limbo.emu.jni.MachineExecutorFactory;
import com.limbo.emu.jni.VMExecutor;
import com.limbo.emu.machine.MachineController;
import com.limbo.emu.machine.MachineExecutor;
import com.limbo.emu.machine.MachineProperty;

/**
 * AIDL 服务端：真正持有并驱动 JNI 原生执行体（{@link VMExecutor}）。
 *
 * <p>客户端只通过 {@link IVmExecutorService} 与本服务通信，因此
 * "App ↔ JNI" 之间的边界被收敛成了一个 Binder 接口。
 *
 * <p>默认与 App 同进程运行（行为与改造前完全一致，只是调用多绕了一层 AIDL）。
 * 如果希望把原生 VM 隔离到独立进程，只需：
 * <ol>
 *   <li>在 AndroidManifest 中给本服务加上 {@code android:process=":vm"}；</li>
 *   <li>把 {@code Config.vmProcessIsolated} 置为 true。</li>
 * </ol>
 * 隔离后，显示层需要把 Surface 通过 {@link IVmExecutorService#setSurface} 交给
 * 服务端（见 {@link AglDisplay}），且不支持强依赖进程内 SurfaceView 的 SDL 后端。
 */
public class VmExecutorService extends Service {
    private static final String TAG = "VmExecutorService";

    /** 已注册的客户端回调（可跨进程）。 */
    private final RemoteCallbackList<IVmExecutorCallback> callbacks = new RemoteCallbackList<>();

    /** 原生执行体，懒创建。 */
    private volatile MachineExecutor localExecutor;

    private final IVmExecutorService.Stub binder = new IVmExecutorService.Stub() {

        @Override
        public void registerCallback(IVmExecutorCallback callback) {
            if (callback != null) {
                callbacks.register(callback);
            }
        }

        @Override
        public void unregisterCallback(IVmExecutorCallback callback) {
            if (callback != null) {
                callbacks.unregister(callback);
            }
        }

        @Override
        public void setMachine(String machineName) {
            MachineController.getInstance().setMachineByName(machineName);
        }

        @Override
        public String start() {
            broadcastVmStarted();
            try {
                return executor().start();
            } finally {
                broadcastVmStopped(null);
            }
        }

        @Override
        public void stopVm(int restart) {
            executor().stopvm(restart);
        }

        @Override
        public String saveVm() {
            return executor().saveVM();
        }

        @Override
        public void continueVm() {
            executor().continueVM();
        }

        @Override
        public int getSaveVmStatus() {
            return executor().getSaveVMStatus().ordinal();
        }

        @Override
        public void setSurface(Surface surface, float refreshRate) {
            try {
                AglDisplay.setSurface(surface, refreshRate);
            } catch (Throwable t) {
                Log.w(TAG, "AglDisplay.setSurface failed: " + t);
            }
        }

        @Override
        public void updateDisplay(int width, int height, int orientation) {
            executor().updateDisplay(width, height, orientation);
        }

        @Override
        public void setFullscreen() {
            executor().setFullscreen();
        }

        @Override
        public void refreshScreen(int value) {
            MachineExecutor ex = executor();
            if (ex instanceof VMExecutor) {
                try {
                    ((VMExecutor) ex).nativeRefreshScreen(value);
                } catch (Throwable t) {
                    Log.w(TAG, "nativeRefreshScreen failed: " + t);
                }
            }
        }

        @Override
        public void setSdlScaleMode(int mode) {
            try {
                VMExecutor.setSDLScaleMode(mode);
            } catch (Throwable t) {
                Log.w(TAG, "setSDLScaleMode(" + mode + ") failed: " + t);
            }
        }

        @Override
        public void aglPointer(float x, float y, int buttons) {
            try {
                AglDisplay.pointer(x, y, buttons);
            } catch (Throwable t) {
                Log.w(TAG, "AglDisplay.pointer failed: " + t);
            }
        }

        @Override
        public void aglScroll(float x, float y) {
            try {
                AglDisplay.scroll(x, y);
            } catch (Throwable t) {
                Log.w(TAG, "AglDisplay.scroll failed: " + t);
            }
        }

        @Override
        public void aglKey(int scanCode, boolean down) {
            try {
                AglDisplay.key(scanCode, down);
            } catch (Throwable t) {
                Log.w(TAG, "AglDisplay.key failed: " + t);
            }
        }

        @Override
        public void setSdlRefreshRate(int refreshMs, int idle) {
            executor().setSdlRefreshRate(refreshMs, idle != 0);
        }

        @Override
        public int getSdlRefreshRate(int idle) {
            return executor().getSdlRefreshRate(idle != 0);
        }

        @Override
        public void sendMouseEvent(int button, int action, int relative, int x, int y) {
            executor().sendMouseEvent(button, action, relative, x, y);
        }

        @Override
        public void setMouseBounds(int xmin, int xmax, int ymin, int ymax) {
            MachineExecutor ex = executor();
            if (ex instanceof VMExecutor) {
                try {
                    ((VMExecutor) ex).nativeMouseBounds(xmin, xmax, ymin, ymax);
                } catch (Throwable t) {
                    Log.w(TAG, "nativeMouseBounds failed: " + t);
                }
            }
        }

        @Override
        public void enableAaudio(int value) {
            executor().enableAaudio(value);
        }

        @Override
        public boolean changeRemovableDevice(int driveOrdinal, String value) {
            MachineProperty property = propertyOf(driveOrdinal);
            if (property == null) {
                return false;
            }
            return executor().changeRemovableDevice(property, value);
        }

        @Override
        public String getDeviceName(int driveOrdinal) {
            MachineProperty property = propertyOf(driveOrdinal);
            if (property == null) {
                return null;
            }
            return executor().getDeviceName(property);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "VmExecutorService created");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        callbacks.kill();
        Log.d(TAG, "VmExecutorService destroyed");
        super.onDestroy();
    }

    /**
     * 懒创建进程内的原生执行器，并把它的分辨率回调转发给所有客户端。
     */
    private synchronized MachineExecutor executor() {
        if (localExecutor == null) {
            MachineExecutor executor =
                    MachineExecutorFactory.createLocalMachineExecutor(MachineController.getInstance());
            executor.addOnResolutionChangedListener(new MachineExecutor.OnResolutionChangedListener() {
                @Override
                public void onResolutionChanged(int vm_width, int vm_height) {
                    int count = callbacks.beginBroadcast();
                    try {
                        for (int i = 0; i < count; i++) {
                            try {
                                callbacks.getBroadcastItem(i).onResolutionChanged(vm_width, vm_height);
                            } catch (RemoteException ignored) {
                                // 客户端已退出，忽略
                            }
                        }
                    } finally {
                        callbacks.finishBroadcast();
                    }
                }
            });
            localExecutor = executor;
        }
        return localExecutor;
    }

    @Nullable
    private static MachineProperty propertyOf(int ordinal) {
        MachineProperty[] values = MachineProperty.values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return null;
    }

    private void broadcastVmStarted() {
        int count = callbacks.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    callbacks.getBroadcastItem(i).onVmStarted();
                } catch (RemoteException ignored) {
                    // 客户端已退出，忽略
                }
            }
        } finally {
            callbacks.finishBroadcast();
        }
    }

    private void broadcastVmStopped(String reason) {
        int count = callbacks.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    callbacks.getBroadcastItem(i).onVmStopped(reason);
                } catch (RemoteException ignored) {
                    // 客户端已退出，忽略
                }
            }
        } finally {
            callbacks.finishBroadcast();
        }
    }
}
