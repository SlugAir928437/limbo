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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;

import com.limbo.emu.main.Config;
import com.limbo.emu.main.LimboApplication;
import com.limbo.emu.machine.Machine;
import com.limbo.emu.machine.MachineController;
import com.limbo.emu.machine.MachineExecutor;
import com.limbo.emu.machine.MachineProperty;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端侧的 {@link MachineExecutor}：把对 JNI 原生执行体的调用全部改成
 * 通过 AIDL(Binder) 发给 {@link VmExecutorService} 执行。
 *
 * <p>它与 {@code com.limbo.emu.jni.VMExecutor} 接口完全一致，因此
 * {@code MachineController} 以及所有上层 UI 代码无需感知通信方式的改变。
 *
 * <p>绑定是异步的：构造时发起 {@code bindService()}，真正需要服务的方法
 * （如 {@link #start()}）会在后台线程上等待连接完成；运行期的高频调用
 * （鼠标事件、刷新率等）如果服务尚未就绪则直接丢弃，避免阻塞 UI。
 */
public class AidlVmExecutor extends MachineExecutor implements ServiceConnection {
    private static final String TAG = "AidlVmExecutor";

    /** 当前进程内唯一的客户端代理，供静态入口（缩放模式/Surface）转发使用。 */
    private static volatile AidlVmExecutor sInstance;

    private final Context context;
    private final AtomicBoolean binding = new AtomicBoolean(false);

    private volatile IVmExecutorService service;
    private volatile CountDownLatch bindLatch = new CountDownLatch(1);

    /** 服务端 → 客户端的事件回调。 */
    private final IVmExecutorCallback.Stub callback = new IVmExecutorCallback.Stub() {
        @Override
        public void onVmStarted() {
            Log.d(TAG, "VM started");
        }

        @Override
        public void onVmStopped(String reason) {
            Log.d(TAG, "VM stopped: " + reason);
        }

        @Override
        public void onResolutionChanged(int width, int height) {
            // 交给父类处理：它会走 MachineController.onVMResolutionChanged()，
            // 而当前执行器正是 MachineController 持有的那个，因此校验会通过。
            AidlVmExecutor.this.onResolutionChanged(width, height);
        }

        @Override
        public void onLog(String line) {
            Log.d(TAG, "[vm] " + line);
        }
    };

    public AidlVmExecutor(MachineController machineController) {
        super(machineController);
        this.context = LimboApplication.getInstance().getApplicationContext();
        sInstance = this;
        bind();
    }

    /** @return 当前进程内的客户端代理，没有则返回 null */
    @Nullable
    public static AidlVmExecutor current() {
        return sInstance;
    }

    // ------------------------------------------------------------------
    // Binder 绑定
    // ------------------------------------------------------------------

    private void bind() {
        if (!binding.compareAndSet(false, true)) {
            return;
        }
        Intent intent = new Intent(Config.vmServiceAction, null, context, VmExecutorService.class);
        try {
            Log.d(TAG, "Binding VmExecutorService");
            context.bindService(intent, this, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            binding.set(false);
            Log.e(TAG, "bindService failed", t);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        Log.d(TAG, "VmExecutorService connected");
        IVmExecutorService svc = IVmExecutorService.Stub.asInterface(binder);
        service = svc;
        try {
            svc.registerCallback(callback);
        } catch (RemoteException e) {
            Log.e(TAG, "registerCallback failed", e);
        }
        bindLatch.countDown();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.w(TAG, "VmExecutorService disconnected");
        service = null;
        bindLatch = new CountDownLatch(1);
        binding.set(false);
        bind();
    }

    /** 在后台线程上等待服务连接就绪。 */
    private boolean awaitService(long timeoutMs) {
        if (service != null) {
            return true;
        }
        if (Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            // 主线程阻塞等待会导致 Binder 回调永远送不进来（死锁），直接放弃。
            Log.w(TAG, "awaitService() on main thread, skipping");
            return false;
        }
        try {
            bindLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return service != null;
    }

    @Nullable
    private IVmExecutorService service() {
        return service;
    }

    // ------------------------------------------------------------------
    // MachineExecutor 实现
    // ------------------------------------------------------------------

    @Override
    public void startService() {
        Intent i = new Intent(Config.ACTION_START, null, LimboApplication.getInstance(),
                MachineController.getInstance().getServiceClass());
        i.putExtras(new Bundle());
        Log.d(TAG, "Starting VM service");
        Context app = LimboApplication.getInstance();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(i);
        } else {
            app.startService(i);
        }
    }

    @Override
    public String start() {
        if (!awaitService(Config.vmServiceBindTimeoutMs)) {
            String msg = "VmExecutorService not connected";
            Log.e(TAG, msg);
            return msg;
        }
        IVmExecutorService svc = service();
        if (svc == null) {
            return "VmExecutorService not connected";
        }
        try {
            Machine machine = getMachine();
            if (machine != null) {
                // 服务端（尤其是独立进程时）没有界面侧的机器上下文，先同步过去。
                svc.setMachine(machine.getName());
            }
            return svc.start();
        } catch (RemoteException e) {
            Log.e(TAG, "start failed", e);
            return e.getMessage();
        }
    }

    @Override
    public void stopvm(final int restart) {
        IVmExecutorService svc = service();
        if (svc == null) {
            return;
        }
        try {
            svc.stopVm(restart);
        } catch (RemoteException e) {
            Log.e(TAG, "stopvm failed", e);
        }
    }

    @Override
    public int getSdlRefreshRate(boolean idle) {
        IVmExecutorService svc = service();
        if (svc == null) {
            return 0;
        }
        try {
            return svc.getSdlRefreshRate(idle ? 1 : 0);
        } catch (RemoteException e) {
            return 0;
        }
    }

    @Override
    public void setSdlRefreshRate(int refreshMs, boolean idle) {
        IVmExecutorService svc = service();
        if (svc == null) {
            return;
        }
        try {
            svc.setSdlRefreshRate(refreshMs, idle ? 1 : 0);
        } catch (RemoteException e) {
            Log.e(TAG, "setSdlRefreshRate failed", e);
        }
    }

    @Override
    public void sendMouseEvent(int button, int action, int relative, float x, float y) {
        IVmExecutorService svc = service();
        if (svc == null) {
            return;
        }
        try {
            svc.sendMouseEvent(button, action, relative, (int) x, (int) y);
        } catch (RemoteException e) {
            Log.e(TAG, "sendMouseEvent failed", e);
        }
    }

    @Override
    public String saveVM() {
        IVmExecutorService svc = service();
        if (svc == null) {
            return "VmExecutorService not connected";
        }
        try {
            return svc.saveVm();
        } catch (RemoteException e) {
            Log.e(TAG, "saveVM failed", e);
            return e.getMessage();
        }
    }

    @Override
    public void continueVM() {
        IVmExecutorService svc = service();
        if (svc == null) {
            return;
        }
        try {
            svc.continueVm();
        } catch (RemoteException e) {
            Log.e(TAG, "continueVM failed", e);
        }
    }

    @Override
    public MachineController.MachineStatus getSaveVMStatus() {
        IVmExecutorService svc = service();
        if (svc == null) {
            return MachineController.MachineStatus.Unknown;
        }
        try {
            int ordinal = svc.getSaveVmStatus();
            MachineController.MachineStatus[] values = MachineController.MachineStatus.values();
            if (ordinal >= 0 && ordinal < values.length) {
                return values[ordinal];
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getSaveVMStatus failed", e);
        }
        return MachineController.MachineStatus.Unknown;
    }

    @Override
    public void enableAaudio(int value) {
        IVmExecutorService svc = service();
        if (svc == null) {
            return;
        }
        try {
            svc.enableAaudio(value);
        } catch (RemoteException e) {
            Log.e(TAG, "enableAaudio failed", e);
        }
    }

    @Override
    public boolean changeRemovableDevice(MachineProperty drive, String diskValue) {
        IVmExecutorService svc = service();
        if (svc == null) {
            return false;
        }
        try {
            return svc.changeRemovableDevice(drive.ordinal(), diskValue);
        } catch (RemoteException e) {
            Log.e(TAG, "changeRemovableDevice failed", e);
            return false;
        }
    }

    @Override
    public String getDeviceName(MachineProperty driveProperty) {
        IVmExecutorService svc = service();
        if (svc == null) {
            return null;
        }
        try {
            return svc.getDeviceName(driveProperty.ordinal());
        } catch (RemoteException e) {
            Log.e(TAG, "getDeviceName failed", e);
            return null;
        }
    }

    @Override
    public void updateDisplay(int width, int height, int orientation) {
        IVmExecutorService svc = service();
        if (svc == null) {
            return;
        }
        try {
            svc.updateDisplay(width, height, orientation);
        } catch (RemoteException e) {
            Log.e(TAG, "updateDisplay failed", e);
        }
    }

    @Override
    public void setFullscreen() {
        IVmExecutorService svc = service();
        if (svc == null) {
            return;
        }
        try {
            svc.setFullscreen();
        } catch (RemoteException e) {
            Log.e(TAG, "setFullscreen failed", e);
        }
    }

    // ------------------------------------------------------------------
    // 额外的转发入口（供渲染/显示层使用）
    // ------------------------------------------------------------------

    /**
     * 把 Surface 交给服务端的原生显示后端。
     *
     * @return 是否成功转发
     */
    public boolean setSurface(Surface surface, float refreshRate) {
        IVmExecutorService svc = service();
        if (svc == null) {
            return false;
        }
        try {
            svc.setSurface(surface, refreshRate);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "setSurface failed", e);
            return false;
        }
    }

    /** 请求服务端立即重绘一帧。 */
    public boolean refreshScreen(int value) {
        IVmExecutorService svc = service();
        if (svc == null) {
            return false;
        }
        try {
            svc.refreshScreen(value);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /** AGL：上报指针位置与按键掩码。 */
    public boolean aglPointer(float x, float y, int buttons) {
        IVmExecutorService svc = service();
        if (svc == null) {
            return false;
        }
        try {
            svc.aglPointer(x, y, buttons);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /** AGL：上报滚轮滚动。 */
    public boolean aglScroll(float x, float y) {
        IVmExecutorService svc = service();
        if (svc == null) {
            return false;
        }
        try {
            svc.aglScroll(x, y);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /** AGL：上报键盘事件（Linux 键码）。 */
    public boolean aglKey(int scanCode, boolean down) {
        IVmExecutorService svc = service();
        if (svc == null) {
            return false;
        }
        try {
            svc.aglKey(scanCode, down);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /** 静态转发：SDL 缩放模式。仅在跨进程隔离时才需要（见 Config#vmProcessIsolated）。 */
    public static boolean forwardSdlScaleMode(int mode) {
        AidlVmExecutor instance = sInstance;
        if (instance == null) {
            return false;
        }
        IVmExecutorService svc = instance.service();
        if (svc == null) {
            return false;
        }
        try {
            svc.setSdlScaleMode(mode);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /** 静态转发：把 Surface 交给服务端的显示后端。 */
    public static boolean forwardSetSurface(Surface surface, float refreshRate) {
        AidlVmExecutor instance = sInstance;
        if (instance == null) {
            return false;
        }
        return instance.setSurface(surface, refreshRate);
    }
}
