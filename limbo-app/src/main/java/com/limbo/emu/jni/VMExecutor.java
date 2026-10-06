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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.limbo.emu.R;
import com.limbo.emu.files.FileUtils;
import com.limbo.emu.machine.Machine;
import com.limbo.emu.machine.MachineAction;
import com.limbo.emu.machine.MachineController;
import com.limbo.emu.machine.MachineExecutor;
import com.limbo.emu.machine.MachineProperty;
import com.limbo.emu.main.Config;
import com.limbo.emu.main.LimboApplication;
import com.limbo.emu.main.LimboSDLActivity;
import com.limbo.emu.main.LimboSettingsManager;
import com.limbo.emu.qmp.QmpClient;
import com.limbo.emu.toast.ToastUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VMExecutor 是 QEMU 虚拟机执行器。
 *
 * <p>它负责：
 * <ul>
 *   <li>把用户在界面上配置的 Machine 参数翻译成 QEMU 命令行参数；</li>
 *   <li>通过 JNI 启动/停止 QEMU 原生进程；</li>
 *   <li>在 gunyah/gzvm 等需要 root 的加速器场景下，通过 su + app_process
 *       启动一个独立的 root 子进程来运行 QEMU；</li>
 *   <li>转发鼠标、键盘、分辨率变化等事件给原生层；</li>
 *   <li>通过 QMP 与运行中的 QEMU 交互（暂停、继续、保存状态、更换可移动设备等）。</li>
 * </ul>
 */
public class VMExecutor extends MachineExecutor {
    private static final String TAG = "VMExecutor";

    /** CD-ROM 在 QMP 中的默认设备名（IDE 总线）。 */
    private static final String cdDeviceName = "ide1-cd0";
    /** 软驱 A 在 QMP 中的设备名。 */
    private static final String fdaDeviceName = "floppy0";
    /** 软驱 B 在 QMP 中的设备名。 */
    private static final String fdbDeviceName = "floppy1";
    /** SD 卡在 QMP 中的设备名。 */
    private static final String sdDeviceName = "sd0";

    // virtio-gpu-gl 的 host memory 窗口大小（hostmem=...）。
    // 这个窗口是真实的 qemu_ram_mmap() 预留（hw/display/virtio-gpu-gl.c），
    // 因此必须足够小以便在手机上分配成功，同时又能容纳常见的 Vulkan blob 工作集。
    private static final String GL_HOSTMEM_SIZE = "2G";

    /** QEMU 2.9.1 版本号，用于区分某些历史参数差异。 */
    private static final int  QEMU_VERSION_20901   = 20901;
    /** 读取日志文件时最多读取的字符数。 */
    private static final int  MAX_READ_CHARS       = 8192;
    /** prepareParams 中 ArrayList 的初始容量。 */
    private static final int  PREPARE_PARAMS_CAP   = 64;
    /** QEMU 正常退出时返回的状态字符串。 */
    private static final String VM_SHUTDOWN        = "VM shutdown";

    /** 当前 VM 的分辨率（由原生 SDL 扩展回调设置）。 */
    private static volatile int vm_width;
    private static volatile int vm_height;

    //TODO: make this a proper singleton but the views should not be able to access it
    /** 当前进程内的 VMExecutor 实例，供静态回调使用。 */
    private static volatile VMExecutor mInstance;

    // Root 子进程相关簿记。
    // 当加速器为 gunyah/gzvm 时，VM 会运行在一个独立启动的 root JVM
    // （RootVmLauncher）中，通过 su + app_process 启动，因为
    // /dev/gunyah 和 /dev/gzvm 是 root-only 的。
    /** root VM 启动脚本文件名。 */
    private static final String ROOT_VM_SCRIPT          = "root_vm.sh";
    /** root VM 的 PID 文件名。 */
    private static final String ROOT_VM_PID             = "root_vm.pid";
    /** root VM 的 stderr 日志文件名。 */
    private static final String ROOT_VM_STDERR          = "root_vm_stderr.log";
    /** root VM 启动超时时间（毫秒）。 */
    private static final long   ROOT_VM_START_TIMEOUT_MS = 20000;
    /** root VM 停止超时时间（毫秒）。 */
    private static final long   ROOT_VM_STOP_TIMEOUT_MS  = 8000;
    /** root VM 轮询间隔（毫秒）。 */
    private static final long   ROOT_VM_POLL_MS          = 300;

    /** su 子进程对象。 */
    private volatile Process rootVmProcess;
    /** root VM 的 PID。 */
    private volatile String  rootVmPid;
    /** 当前是否处于 root VM 模式。 */
    private volatile boolean rootVmMode;
    /** 父进程是否已请求停止 root VM，用于区分用户主动关机与子进程异常退出。 */
    private volatile boolean rootVmStopRequested;
    /** KVM root 启动选择的结果：未决定 / 当前进程 / root 子进程。 */
    private static final int KVM_CHOICE_UNDECIDED = 0;
    private static final int KVM_CHOICE_NORMAL    = 1;
    private static final int KVM_CHOICE_ROOT      = 2;

    private final AtomicInteger kvmChoice =
            new AtomicInteger(KVM_CHOICE_UNDECIDED);
    private final Object kvmChoiceLock = new Object();

    VMExecutor(MachineController machineController) {
        super(machineController);
        mInstance = this;
    }

    /**
     * 当虚拟机分辨率变化时由 SDL 兼容扩展调用（见 jni/compat/sdl-extensions）。
     *
     * @param width  宽度
     * @param height 高度
     */
    @Keep public static void onVMResolutionChanged(int width, int height) {
        vm_width = width;
        vm_height = height;
        VMExecutor inst = mInstance;
        if (inst != null) {
            inst.onResolutionChanged(width, height);
        }
    }

    //JNI Methods
    /** 启动 QEMU 原生进程。 */
    private native String start(String storage_dir, String base_dir,
                                String lib_filename, String lib_path,
                                Object[] params);

    /** 停止 QEMU 原生进程，restart 非 0 时表示重启。 */
    private native String stop(int restart);

    /** 设置默认刷新率。 */
    public native void setSDLRefreshRateDefault(int value);

    /** 设置空闲刷新率。 */
    public native void setSDLRefreshRateIdle(int value);

    /** 获取默认刷新率。 */
    public native int getSDLRefreshRateDefault();

    /** 获取空闲刷新率。 */
    public native int getSDLRefreshRateIdle();

    /** 发送鼠标事件。 */
    public native void nativeMouseEvent(int button, int action, int relative, int x, int y);

    /** 设置鼠标边界，用于绝对指针设备。 */
    public native void nativeMouseBounds(int xmin, int xmax, int ymin, int ymax);

    /** 切换全屏。 */
    public native void nativeFullscreen();

    /** 刷新屏幕。 */
    public native void nativeRefreshScreen(int value);

    /** 启用/禁用 AAudio。 */
    public native void nativeEnableAaudio(int value, String aaudioLibName, String aaudioLibPath);

    /**
     * 设置 SDL 显示方式（缩放模式），对应 QEMU 侧 ui/sdl2.c 的
     * limbo_sdl_scale_mode：0 = 拉伸至全屏，1 = 等比缩放，2 = 原始分辨率 1:1。
     *
     * <p>声明成 static，方便在拿到 VMExecutor 实例之前就设定（例如 Activity
     * 启动时按用户上次的选择预设）；值会缓存，等 QEMU 库加载后由 start() 下发，
     * 运行中再次调用则立即生效。
     */
    public static native void setSDLScaleMode(int mode);

    /**
     * setSDLScaleMode() 的带保护版本：native 库还没加载（或构建里没带该符号）时
     * 只记日志，不抛出 UnsatisfiedLinkError。
     */
    public static void setSdlScaleMode(int mode) {
        try {
            setSDLScaleMode(mode);
        } catch (Throwable t) {
            Log.w(TAG, "setSDLScaleMode(" + mode + ") 失败: " + t);
        }
    }

    /**
     * 以 QEMU 格式打印参数，便于调试。
     *
     * @param params 参数数组
     */
    public void printParams(@NonNull String[] params) {
        Log.d(TAG, "Params:");
        for (int i = 0; i < params.length; i++) {
            Log.d(TAG, i + ": " + params[i]);
        }
    }

    // Translate to QEMU format
    /**
     * 获取声卡设备名。如果 SDL 声音未启用或声卡为 none，则返回 null。
     */
    private String getSoundCard() {
        if (Config.enableSDLSound && getMachine().getSoundCard() != null
                && !getMachine().getSoundCard().equalsIgnoreCase("none"))
            return getMachine().getSoundCard();
        return null;
    }

    /**
     * 根据当前架构选择对应的 QEMU 系统库文件名。
     */
    private String getQemuLibrary() {
        switch (LimboApplication.arch) {
            case x86:
                return "libqemu-system-i386.so";
            case x86_64:
                return "libqemu-system-x86_64.so";
            case arm:
                return "libqemu-system-arm.so";
            case arm64:
                return "libqemu-system-aarch64.so";
            case ia64:
                return "libqemu-system-ia64.so";
            case ia64w:
                return "libqemu-system-ia64w.so";
            default:
                throw new IllegalStateException("Unexpected value: " + LimboApplication.arch);
        }
    }

    /**
     * 获取保存状态文件的完整路径。
     */
    @NonNull
    private String getSaveStateName() {
        String machineSaveDirectory = MachineController.getInstance().getMachineSaveDir();
        return machineSaveDirectory + "/" + Config.stateFilename;
    }

    /**
     * 组装完整的 QEMU 参数列表。
     *
     * <p>参数按固定顺序添加：UI、CPU/主板、驱动器、启动项、BIOS、显卡、音频、
     * 网络、通用选项、状态恢复、高级选项、加速器。加速器选项最后添加，
     * 因为 QEMU 只认第一个同名选项，放在最后可以避免被 extra params 覆盖。
     */
    private String[] prepareParams(Context context) throws Exception {
        ArrayList<String> paramsList = new ArrayList<>(PREPARE_PARAMS_CAP);
        paramsList.add(getQemuLibrary());
        addUIOptions(context, paramsList);
        addCpuBoardOptions(paramsList);
        addDrives(paramsList);
        addBootOptions(paramsList);
        addBIOSOption(paramsList);
        addGraphicsOptions(paramsList);
        addAudioOptions(paramsList);
        addNetworkOptions(paramsList);
        addGenericOptions(context, paramsList);
        addStateOptions(paramsList);
        addAdvancedOptions(paramsList);
        addAccelerationOptions(paramsList);
        return paramsList.toArray(new String[0]);
    }

    /**
     * 如果虚拟机处于暂停状态，添加 -incoming 参数以恢复保存的状态。
     *
     * @param paramsList 现有参数列表
     */
    private void addStateOptions(ArrayList<String> paramsList) {
        if (MachineController.getInstance().isPaused() && !getSaveStateName().isEmpty()) {
            // 使用 "file:" 方案让 QEMU 自己打开并持有状态文件。
            // 传 "fd:N" 会让 QEMU 在 incoming migration 完成时关闭 fd，
            // 这会触发 Android 的 fdsan（SIGABRT），因为该 fd 属于
            // FileUtils.get_fd() 打开的 ParcelFileDescriptor。
            paramsList.add("-incoming");
            paramsList.add("file:" + getSaveStateName());
        }
    }

    /**
     * 添加 UI 相关选项：VNC、monitor/serial/parallel 控制台、显示后端、键盘、鼠标。
     */
    private void addUIOptions(Context context, ArrayList<String> paramsList) {
        String ui = getMachine().getUI();
        boolean gtk = "GTK".equals(ui);
        if (MachineController.getInstance().isVNCEnabled() && !gtk) {
            paramsList.add("-vnc");
            String vncParam = "";
            if (LimboSettingsManager.getVNCEnablePassword(context)) {
                //TODO: Allow connections from External Use with x509 auth and TLS for encryption
                vncParam += ":1";
            } else {
                // 仅允许 localhost 通过本地 socket 连接，无密码
                vncParam += Config.defaultVNCHost + ":" + Config.defaultVNCPort;
            }
            if (LimboSettingsManager.getVNCEnablePassword(context))
                vncParam += ",password";

            paramsList.add(vncParam);

            // 允许 monitor 控制台，虽然它只对 VNC 有支持；
            // Android 的 SDL 不支持多于一个窗口。
            paramsList.add("-monitor");
            paramsList.add("vc");

        } else {
            // gtk 允许多窗口
            if (!gtk) {
                // 通过 TCP (server,nowait) 暴露 monitor/serial/parallel，
                // 这样 nc 模块可以连接查看控制台。使用 raw tcp（非 telnet）
                // 不会打开 SDL 窗口，避免 SDL 多窗口限制。
                paramsList.add("-monitor");
                paramsList.add("tcp:127.0.0.1:" + Config.monitorPort + ",server,nowait");

                paramsList.add("-serial");
                paramsList.add("tcp:127.0.0.1:" + Config.serialPort + ",server,nowait");

                paramsList.add("-parallel");
                paramsList.add("tcp:127.0.0.1:" + Config.parallelPort + ",server,nowait");
            }
            paramsList.add("-display");
            if (gtk) {
                // GTK4 Android 后端（由 activity 侧的 LimboGtk 初始化）
                paramsList.add("gtk" + getDisplayGLOption());
            } else {
                // SDL 后端必须加 show-cursor=on：客户机装上真正的 GPU/KMS 驱动后，
                // 合成器改用 DRM 硬件光标平面，不再把光标画进帧缓冲，
                // 内核驱动通过 virtio-gpu 的 UPDATE_CURSOR 通知 QEMU，
                // hw/display/virtio-gpu.c 调用 dpy_cursor_define()，
                // ui/sdl2.c 的 sdl_mouse_define() 再用 SDL_CreateColorCursor()
                // 造一个客户机光标；而 Android 的 SDL 视频后端没有实现光标接口
                // （jni/SDL2/src/video/android 下没有任何 Cursor 实现），
                // guest_sprite 为空，且 sdl_hide_cursor() 已经把宿主机指针关掉
                // （SDL_ShowCursor(SDL_DISABLE) + 相对鼠标模式），于是屏幕上
                // 一个指针都不剩。show-cursor=on 会让 sdl_hide_cursor()/
                // sdl_show_cursor() 直接 return，QEMU 完全不碰宿主机指针，
                // Android 自己的指针始终可见（该选项自 QEMU 6.0 起替代已删除的
                // -show-cursor，见 qemu-options.hx 的 "sdl[,gl=on|core|es|off]"
                // "[,grab-mod=<mod>][,show-cursor=on|off]"）。
                paramsList.add("sdl" + getDisplayGLOption() + ",show-cursor=on");
            }
        }

        if (getMachine().getKeyboard() != null) {
            paramsList.add("-k");
            paramsList.add(getMachine().getKeyboard());
        }

        if (getMachine().getMouse() != null && !getMachine().getMouse().equals("ps2")) {
            String mouseDevice = getMachine().getMouse();
            if (mouseDevice.startsWith("virtio-")) {
                // VirtIO 输入设备位于 virtio 总线上，因此不能添加 -usb。
                // 单独的 virtio-tablet-pci 会让 guest 没有键盘，
                // 所以与 virtio-keyboard-pci 配对。
                paramsList.add("-device");
                paramsList.add(mouseDevice);
                if (mouseDevice.startsWith("virtio-tablet-pci")) {
                    paramsList.add("-device");
                    paramsList.add("virtio-keyboard-pci");
                }
            } else {
                paramsList.add("-usb");
                paramsList.add("-device");
                paramsList.add(mouseDevice);
                // 对于 ia64 架构的虚拟机，需要添加 usb-kbd 设备以支持键鼠
                // 在 i8042=off 的情况下无需添加此设备（在 QEMU 中自动添加）
//            if (LimboApplication.arch == Config.Arch.ia64 || LimboApplication.arch == Config.Arch.ia64w) {
//                paramsList.add("-device");
//                paramsList.add("usb-kbd");
//            }
            }
        }
        // FreshingAir: 在后面的位置加 USB 控制器，
        // 会被 usb-tablet “忽略”，故而找不到控制器
        // 挪到前面，让 usb-tablet 识别
        addUSBController(paramsList);
    }

    /**
     * virtio-gpu-gl-pci 只有在显示后端启用 OpenGL 时才能工作：
     * 否则 QEMU 在设备 realize 时会中止，报
     * "The display backend does not have OpenGL support enabled"
     * (hw/display/virtio-gpu-gl.c)。因此该选项与显示后端一起提供，
     * 而不是留给机器的 extra params。
     *
     * @return 当机器使用 VirGL (GL) virtio-gpu 设备时返回 ",gl=on"，否则返回空字符串
     */
    @NonNull private String getDisplayGLOption() {
        String vga = getMachine().getVga();
        return (vga != null && vga.startsWith("virtio-gpu-gl")) ? ",gl=on" : "";
    }

    /**
     * 添加高级选项：USB 控制器以及用户自定义 extra params。
     */
    private void addAdvancedOptions(ArrayList<String> paramsList) {
        String extra = getMachine().getExtraParams();
        if (extra != null && !extra.trim().isEmpty()) {
            String[] paramsTmp = extra.split(" ");
            paramsList.addAll(Arrays.asList(paramsTmp));
        }
    }

    /**
     * 添加高级设置中选择的 USB 控制器/HID 设备。
     *
     * <p>该值是 QEMU 设备名（或 "None" 表示不显式指定控制器）。
     * 控制器型号直接作为 {@code -device} 传入；UI 只提供目标引擎中
     * 实际编译进去的型号（见 ArchDefinitions#getUsbControllerValues），
     * 因此 IA-64 构建（缺少 xHCI 设备）只能选 EHCI/UHCI。
     *
     * <p>{@code usb-kbd} 是 USB HID 设备而非控制器，因此必须先有 USB 总线：
     * 用 {@code -usb} 启用机器默认控制器（与 addUIOptions 对 USB 鼠标的处理一致）。
     * 仅当 USB 鼠标尚未启用 {@code -usb} 时才添加。
     */
    private void addUSBController(ArrayList<String> paramsList) {
        String usbController = getMachine().getUsbController();
        if (usbController == null)
            return;
        String device = usbController.trim();
        if (device.isEmpty() || "None".equalsIgnoreCase(device))
            return;

        if ("usb-kbd".equals(device)) {
            if (!paramsList.contains("-usb"))
                paramsList.add("-usb");
            paramsList.add("-device");
            paramsList.add("usb-kbd");
        } else {
            paramsList.add("-device");
            paramsList.add(device);
        }
    }

    /**
     * 添加音频设备选项。
     */
    private void addAudioOptions(ArrayList<String> paramsList) {
        String soundCard = getSoundCard();
        if (soundCard != null) {
            // virtio-sound 默认暴露一个 capture 和一个 playback 流，
            // QEMU 会把它们拆成 "out" 和 "in" 两半（hw/audio/virtio-snd.c）。
            // 输入流会让 SDL 打开 Android 录音设备，而应用没有申请
            // RECORD_AUDIO 权限，因此总是失败（"Could not create a backend
            // for voice 'virtio-sound.in'"）。只保留一个流即可只播放。
            if (soundCard.startsWith("virtio-sound")) {
                soundCard += ",streams=1";
            }
            paramsList.add("-device");
            paramsList.add(soundCard);
        }
    }

    /**
     * 添加通用选项：-L、QMP、trace、tb-size、realtime/overcommit、rtc。
     */
    private void addGenericOptions(Context context, @NonNull ArrayList<String> paramsList) {
        paramsList.add("-L");
        paramsList.add(LimboApplication.getBasefileDir());
        if (LimboSettingsManager.getEnableQmp(context)) {
            paramsList.add("-qmp");
            if (getQMPAllowExternal()) {
                String qmpParams = "tcp:";
                qmpParams += (":" + Config.QMPPort);
                qmpParams += ",server,nowait";
                paramsList.add(qmpParams);
            } else {
                // 使用 Unix 本地域套接字，仅限本地连接
                String qmpParams = "unix:";
                qmpParams += LimboApplication.getLocalQMPSocketPath();
                qmpParams += ",server,nowait";
                paramsList.add(qmpParams);
            }
        }

        // 启用 tracing 日志
        if (Config.enableTracingLog) {
            paramsList.add("-D");
            paramsList.add(Config.traceLogFile);
            paramsList.add("--trace");
            paramsList.add("events=" + Config.traceEventsFile);
            paramsList.add("--trace");
            paramsList.add("file=" + Config.traceDir);
        }

        if (Config.overrideTbSize) {
            paramsList.add("-tb-size");
            paramsList.add(Config.tbSize); // 不要调大，会崩溃
        }

        if (LimboApplication.getQemuVersion() == QEMU_VERSION_20901) {
            paramsList.add("-realtime");
            paramsList.add("mlock=off");
        } else {
            paramsList.add("-overcommit");
            paramsList.add("mem-lock=off");
        }

        paramsList.add("-rtc");
        paramsList.add("base=localtime");
    }

    /**
     * 添加 CPU 和主板相关选项：-smp、-M、-cpu、-m，以及 ACPI/HPET 禁用。
     */
    private void addCpuBoardOptions(ArrayList<String> paramsList) {
        //XXX: SMP 对某些 guest OS 不能正常工作，
        // 因此只在 KVM 下启用多核；
        // 普通模拟除非启用 mttcg 否则没有收益，而 mttcg 对 x86 guest 尚不可用。
        if (getMachine().getCpuNum() > 1) {
            paramsList.add("-smp");
            paramsList.add(getMachine().getCpuNum() + "");
        }
        if (getMachineType() != null && !getMachineType().equals("Default")) {
            String machineParams = getMachineType();
            // 仅 IA-64：当用户禁用 i8042 PS/2 控制器时追加 i8042=off，
            // 启用 NVRAM 时追加 nvram=<path>（未显式设置路径时使用应用管理的文件）。
            // Windows XP / Server 2003 IA64 文本模式安装无法使用 PS/2，
            // 因此 i8042=off 会让 QEMU 挂载 USB 键盘；没有它，
            // "Press any key to boot from CD" 提示会超时，加载器在
            // "Continuing normal boot." 后挂起。其他架构不能收到这些选项。
            if (LimboApplication.arch == Config.Arch.ia64 || LimboApplication.arch == Config.Arch.ia64w) {
                // "i8042" 机器属性只存在于 IA-64 VPC 机器上
                // (itanium-vpc / ia64-vpc / itanium2-vpc)。HP 工作站型号
                // (hp-i2000 / hp-zx2000 / hp-zx6000) 没有 i8042 控制器，
                // 因此追加 i8042=off 会让 QEMU 拒绝该机器并启动失败。
                if (machineParams.contains("vpc") && getMachine().getDisableI8042() == 1) {
                    machineParams += ",i8042=off";
                }
                if (getMachine().getEnableNvram() == 1) {
                    String nvramPath = getMachine().getNvramPath();
                    if (nvramPath == null || nvramPath.trim().isEmpty()) {
                        nvramPath = LimboApplication.getNvramFile();
                    }
                    machineParams += ",nvram=" + nvramPath;
                }
            }
            paramsList.add("-M");
            paramsList.add(machineParams);
        }

        //FIXME: 引用有问题，导致 sparc qemu 找不到 cpu 定义；
        // 目前暂时从 sparc 的 cpu 下拉列表中移除相关项。
        String cpu = getMachine().getCpu();
        if (cpu != null && cpu.contains(" ")) {
            cpu = "'" + cpu + "'"; // XXX: sparc cpu 名称需要加引号
        }

        //XXX: 对 x86 禁用 tsc 特性，因为某些 guest 会内核 panic；
        // 如果用户没有指定 cpu，则使用内部的 qemu32。
        //
        // ",-tsc" 后缀会清除 CPUID.1:EDX.TSC，只有 32 位 guest 能忍受
        // （它们会回退到 PIT/PM 定时器）。64 位 Windows 内核会把不提供
        // 时间戳计数器的处理器视为不支持，并在启动时 bugcheck
        // STOP 0x0000005D (UNSUPPORTED_PROCESSOR) —— 无论用户选了哪个
        // -cpu 型号，因为该后缀会追加到每个型号上。因此该 workaround
        // 绝不应用于 x86_64 目标。
        if (getMachine().getDisableTSC() == 1 && LimboApplication.arch == Config.Arch.x86) {
            if (cpu == null || cpu.equals("Default")) {
                cpu = "qemu32";
            }
            cpu += ",-tsc";
        }

        // ACPI/HPET 禁用是 x86 独有的概念。QEMU 9.0 移除了 -no-acpi/-no-hpet
        // 开关，改为机器属性（acpi=off / hpet=off）；这些属性只存在于 x86 机器上，
        // 因此其他目标（ia64、arm 等）完全不能收到它们。
        if (LimboApplication.arch == Config.Arch.x86 || LimboApplication.arch == Config.Arch.x86_64) {
            if (getMachine().getDisableAcpi() != 0) {
                if (LimboApplication.getQemuVersion() >= 90000) {
                    paramsList.add("-machine");
                    paramsList.add("acpi=off");
                } else {
                    paramsList.add("-no-acpi");
                }
            }
            if (getMachine().getDisableHPET() != 0) {
                if (LimboApplication.getQemuVersion() >= 90000) {
                    paramsList.add("-machine");
                    paramsList.add("hpet=off");
                } else {
                    paramsList.add("-no-hpet");
                }
            }
        }

        // HP 工作站型号 (hp-i2000 / hp-zx2000 / hp-zx6000) 硬性要求
        // 它们自己的 CPU (merced-800 / mckinley-900 / madison-1500)，
        // 拒绝 -cpu 覆盖，因此对它们绝不要传用户选择的 CPU；
        // QEMU 会使用机器默认 CPU。
        boolean isHpMachine = getMachineType() != null && getMachineType().startsWith("hp-");
        if (!isHpMachine && cpu != null && !cpu.equals("Default")) {
            paramsList.add("-cpu");
            paramsList.add(cpu);
        }

        paramsList.add("-m");
        paramsList.add(getMachine().getMemory() + "");

        // Gunyah (aarch64) guest 不能收到 ALS 参考选项
        // "-M virt,confidential-guest-support=prot0" 和
        // "-object arm-confidential-guest,id=prot0,swiotlb-size=256M"。
        // 本应用构建的 QEMU（v11.0.0 加上
        // patches/qemu-v11.0.0-gunyah-gzvm-accel.patch）没有实现该对象：
        // 它的 swiotlb 缓冲区是加速器状态的内部字段
        // (GUNYAHState.swiotlb_size，在 gunyah_init() 中用默认值填充)，
        // 而 gunyah 代码路径由加速器自身选择 (gunyah_enabled())。
        // QEMU 会用 error_fatal 拒绝未知的 "-object" -> 在 qemu_init()
        // 运行期间 exit(1)，导致 VM 在启动前就中止。
    }


    /**
     * 添加加速器选项。
     *
     * <p>注意：加速器选项放在 extra params 之后添加，因为 QEMU 只认第一个
     * 同名选项，这样 extra params 无法覆盖它。
     */
    private void addAccelerationOptions(@NonNull ArrayList<String> paramsList) {

        // XXX: 我们在 extra params 之后添加加速器选项，
        // 因为 QEMU 只应用该选项的第一个实例，
        // 这样 extra params 无法覆盖它。
        String accelMode = getMachine().getAccelMode();
        paramsList.add("-accel");
        if (Machine.ACCEL_KVM.equals(accelMode)) {
            paramsList.add("kvm");
        } else if (Machine.ACCEL_GUNYAH.equals(accelMode)) {
            paramsList.add("gunyah");
        } else if (Machine.ACCEL_GZVM.equals(accelMode)) {
            paramsList.add("gzvm");
        } else {
            // 默认：TCG，由 MTTCG 开关控制线程模式
            paramsList.add(getMachine().getEnableMTTCG() != 0
                    ? "tcg,thread=multi"
                    : "tcg,thread=single");
        }
    }

    /**
     * 获取机器类型。对于 x86/x86_64，如果未设置则默认 "pc"。
     */
    private String getMachineType() {
        String machineType = getMachine().getMachineType();
        if ((LimboApplication.arch == Config.Arch.x86 || LimboApplication.arch == Config.Arch.x86_64)
                && machineType == null) {
            machineType = "pc";
        }
        return machineType;
    }

    /**
     * 添加网络选项：-net user/tap/none 以及 -net nic。
     */
    private void addNetworkOptions(ArrayList<String> paramsList) throws Exception {

        String network = getNetCfg();
        if (network != null) {
            paramsList.add("-net");
            switch (network) {
                case "user":
                    StringBuilder netParams = new StringBuilder(network);
                    String hostFwd = getHostFwd();
                    if (hostFwd != null) {

                        //hostfwd=[tcp|udp]:[hostaddr]:hostport-[guestaddr]:guestport{,hostfwd=...}
                        // 示例：将 guest 22 端口转发到 host 2222 端口：
                        // hostfwd=tcp::2222-:22
                        if (hostFwd.startsWith("hostfwd")) {
                            throw new Exception("Invalid format for Host Forward, should be: tcp:hostport1:guestport1,udp:hostport2:questport2,...");
                        }
                        String[] hostFwdParams = hostFwd.split(",");
                        for (String hostFwdParam : hostFwdParams) {
                            netParams.append(",");
                            String[] hostfwdparam = hostFwdParam.split(":");
                            netParams.append("hostfwd=").append(hostfwdparam[0]).append("::").append(hostfwdparam[1]).append("-:").append(hostfwdparam[2]);
                        }
                    }
                    paramsList.add(netParams.toString());
                    break;
                case "tap":
                    paramsList.add("tap,vlan=0,ifname=tap0,script=no");
                    break;
                case "none":
                    paramsList.add("none");
                    break;
                default:
                    // 未知接口
                    paramsList.add("none");
                    break;
            }
        }

        String networkCard = getNicCard();
        if (networkCard != null) {
            paramsList.add("-net");
            String nicParams = "nic";
            if ("tap".equals(network))
                nicParams += ",vlan=0";
            if (!networkCard.equals("Default"))
                nicParams += (",model=" + networkCard);
            paramsList.add(nicParams);
        }
    }

    /**
     * 获取主机端口转发配置，仅在 User 网络模式下有效。
     */
    private String getHostFwd() {
        if (getMachine().getNetwork().equals("User")) {
            if (getMachine().getHostFwd() != null && !getMachine().getHostFwd().isEmpty())
                return getMachine().getHostFwd();
        }
        return null;
    }

    /**
     * 获取网卡型号，网络为 None 时返回 null。
     */
    private String getNicCard() {
        if (getMachine().getNetwork() == null || getMachine().getNetwork().equals("None")) {
            return null;
        } else if (getMachine().getNetwork().equals("User")) {
            return getMachine().getNetworkCard();
        } else if (getMachine().getNetwork().equals("TAP")) {
            return getMachine().getNetworkCard();
        }
        return null;
    }

    /**
     * 获取网络配置类型：none/user/tap。
     */
    private String getNetCfg() {
        if (getMachine().getNetwork() == null || getMachine().getNetwork().equals("None")) {
            return "none";
        } else if (getMachine().getNetwork().equals("User")) {
            return "user";
        } else if (getMachine().getNetwork().equals("TAP")) {
            return "tap";
        }
        return null;
    }

    /**
     * 添加显卡选项。
     *
     * <p>virtio-gpu 系列使用 -device 挂载，并配合 -vga none 避免与主板默认
     * VGA 冲突；virtio-gpu-gl 还会追加 blob/hostmem/venus 属性。
     */
    private void addGraphicsOptions(ArrayList<String> paramsList) {
        String vga = getMachine().getVga();
        if (vga == null) {
            return;
        }
        if (vga.equals("Default")) {
            // 不做任何事
        } else if (vga.startsWith("virtio-gpu")) {
            // virtio-gpu-pci / virtio-gpu-gl-pci 是 PCI 设备，不是 -vga
            // bios 类型，因此用 -device 挂载。除非给出 -vga none，
            // 否则主板会再添加自己的默认 (std) VGA，导致 guest 有两个
            // 适配器、QEMU 有两个控制台 —— Android 显示后端只能显示其中
            // 一个，因此 virtio-gpu 输出永远不可见。
            String vgaDevice = vga;
            // Venus（Vulkan over VirGL）只由 GL 设备提供：
            // "venus" 属性声明在 hw/display/virtio-gpu-gl.c 中，
            // 因此普通 virtio-gpu-pci 会把它当作未知属性拒绝。
            // venus=on 让 QEMU 宣告 VIRTIO_GPU_CAPSET_VENUS 并向
            // virglrenderer 请求 VIRGL_RENDERER_VENUS | VIRGL_RENDERER_RENDER_SERVER，
            // 因此 renderer 也必须以 venus 支持构建 (-Dvenus)。
            //
            // venus 还需要 host blobs：除非同时给出 blob=on 和
            // hostmem=<size>，否则 hw/display/virtio-gpu.c 的 realize 会失败，
            // 报 "venus requires enabled blob and hostmem options"，
            // 因此这两个前提条件随 venus 属性一起带上。
            if (vgaDevice.startsWith("virtio-gpu-gl")) {
                if (!vgaDevice.contains(",blob=")) {
                    vgaDevice += ",blob=on";
                }
                if (!vgaDevice.contains(",hostmem=")) {
                    vgaDevice += ",hostmem=" + GL_HOSTMEM_SIZE;
                }
                if (!vgaDevice.contains(",venus=")) {
                    vgaDevice += ",venus=on";
                }
            }
            paramsList.add("-vga");
            paramsList.add("none");
            paramsList.add("-device");
            paramsList.add(vgaDevice);
        } else if (vga.equals("nographic")) {
            paramsList.add("-nographic");
        } else {
            paramsList.add("-vga");
            paramsList.add(vga);
        }
    }

    /**
     * 添加启动选项：-boot、-kernel、-initrd、-append。
     */
    private void addBootOptions(ArrayList<String> paramsList) {
        if (getBootDevice() != null) {
            paramsList.add("-boot");
            paramsList.add(getBootDevice());
        }

        String kernel = getKernel();
        if (kernel != null && !kernel.isEmpty()) {
            paramsList.add("-kernel");
            paramsList.add(kernel);
        }

        String initrd = getInitRd();
        if (initrd != null && !initrd.isEmpty()) {
            paramsList.add("-initrd");
            paramsList.add(initrd);
        }

        if (getMachine().getAppend() != null && !getMachine().getAppend().isEmpty()) {
            paramsList.add("-append");
            paramsList.add(getMachine().getAppend());
        }
    }

    /**
     * 获取 -boot 参数值。ARM/ARM64 不适用，返回 null。
     */
    @Nullable private String getBootDevice() {
        if (LimboApplication.arch == Config.Arch.arm || LimboApplication.arch == Config.Arch.arm64) {
            return null;
        } else if (getMachine().getBootDevice().equals("Default")) {
            return null;
        } else if (getMachine().getBootDevice().equals("CDROM")) {
            return "d";
        } else if (getMachine().getBootDevice().equals("Floppy")) {
            return "a";
        } else if (getMachine().getBootDevice().equals("Hard Disk")) {
            return "c";
        }
        return null;
    }

    /**
     * 添加 "-bios" 选项。如果用户在 BIOS 下拉框选择了固件（存储在 machine 中的
     * assets/roms 文件名），则使用该文件；否则使用应用 assets 中附带的 SeaBIOS。
     * SeaBIOS 回退仅适用于 x86/x86_64（以及 IA-64）机器：在 ARM 板上 "-bios"
     * 要么被忽略（Cortex-M 板 microbit、lm3s*、netduino* 只从 "-kernel" 加载固件），
     * 要么被当作地址 0 处的第一条指令源加载（raspi/vexpress/aspeed/cubieboard/orangepi），
     * 此时 x86 blob 会被当作固件执行。
     */
    private void addBIOSOption(ArrayList<String> paramsList) {
        Machine machine = getMachine();
        String bios = machine != null ? machine.getBios() : null;
        if (bios != null && !bios.isEmpty() && !bios.equals("None")) {
            // 用户从 BIOS 下拉框选择的固件
            File biosFile = new File(bios);
            if (!biosFile.isAbsolute()) {
                biosFile = new File(LimboApplication.getBasefileDir() + bios);
            }
            if (biosFile.exists()) {
                paramsList.add("-bios");
                paramsList.add(biosFile.getAbsolutePath());
                return;
            }
            Log.w(TAG, "BIOS file not found: " + biosFile.getPath());
            return;
        }
        // SeaBIOS 是 x86 固件：绝不要在 ARM 上回退到它。那里读取 "-bios" 的板子
        // 会把它当作 reset/boot 固件加载，而 Cortex-M 板则完全忽略它 ——
        // 这两种情况下给它们 bios-256k.bin 只会掩盖没有可用固件的事实。
        if (LimboApplication.arch == Config.Arch.arm
                || LimboApplication.arch == Config.Arch.arm64) {
            return;
        }
        // QEMU 10.x 在 PC 机器上默认使用 bios-256k.bin；bios.bin 是
        // 作为回退保留的旧版 128K SeaBIOS。
        String[] biosCandidates = {"bios-256k.bin", "bios.bin"};
        for (String biosFile : biosCandidates) {
            File biosF = new File(LimboApplication.getBasefileDir() + biosFile);
            if (biosF.exists()) {
                paramsList.add("-bios");
                paramsList.add(biosF.getAbsolutePath());
                return;
            }
        }
    }

    /** 获取 initrd 路径，并编码 document file path。 */
    private String getInitRd() {
        return FileUtils.encodeDocumentFilePath(getMachine().getInitRd());
    }

    /** 获取 kernel 路径，并编码 document file path。 */
    private String getKernel() {
        return FileUtils.encodeDocumentFilePath(getMachine().getKernel());
    }

    /** 获取驱动器文件路径，None 返回 null，否则编码。 */
    public String getDriveFilePath(String driveFilePath) {
        String imgPath = driveFilePath;
        if (imgPath == null || imgPath.equals("None"))
            return null;
        imgPath = FileUtils.encodeDocumentFilePath(imgPath);
        return imgPath;
    }

    /** 判断镜像是否为 raw 格式（.img/.raw）。 */
    private boolean isRawImage(String imagePath) {
        if (imagePath == null)
            return false;
        String lower = imagePath.toLowerCase(Locale.US);
        return lower.endsWith(".img") || lower.endsWith(".raw");
    }

    /**
     * 解析 -drive if= 接口。null/空 回退到 "ide"（QEMU 默认总线），
     * 与之前未暴露 per-drive 接口时行为一致。
     */
    @NonNull private String resolveDriveInterface(String iface) {
        if (iface == null || iface.trim().isEmpty())
            return "ide";
        return iface;
    }

    /**
     * 解析 -drive format=。null/空/"auto" 保持旧行为：
     * 硬盘只有 raw 镜像才用 "raw"（否则自动检测），CD-ROM 始终用 "raw"。
     * 用户设置的任何具体格式都原样使用。
     *
     * @param explicit 存储的 per-drive 格式（未设置时为 null）
     * @param path     镜像文件路径（用于 raw 检测）
     * @param isDisk   true 表示硬盘，false 表示 CD-ROM
     */
    @Nullable
    private String resolveDriveFormat(String explicit, String path, boolean isDisk) {
        if (explicit == null || explicit.trim().isEmpty() || explicit.equals("auto")) {
            return isDisk ? (isRawImage(path) ? "raw" : null) : "raw";
        }
        return explicit;
    }

    /**
     * 解析 -drive cache=。用户设置的 per-drive 值原样使用；
     * null/空 回退到全局 cache 设置（用户选择 "default" 时该值已为 null）。
     */
    private String resolveDriveCache(String explicit, String fallback) {
        if (explicit != null && !explicit.trim().isEmpty()) {
            return explicit.trim();
        }
        return fallback;
    }

    /**
     * 以统一的 "-drive" 参数形式输出所有存储设备
     * （HDA..HDD、CDROM、FDA/FDB、SD 卡以及共享文件夹）。
     */
    public void addDrives(ArrayList<String> paramsList) {
        // 全局回退 cache 模式，来自设置（"default"/空 -> 不添加 cache=）。
        String globalCache = LimboSettingsManager.getDiskCache(LimboApplication.getInstance());
        if (globalCache == null || globalCache.equals("default"))
            globalCache = null;

        // 硬盘 HDA..HDD。if= 来自机器的 per-drive 接口
        //（null/空 -> "ide"，QEMU 默认）。format= 来自机器的 per-drive 格式
        //（设置时），否则使用旧的 auto/raw 检测。cache= 来自机器的 per-drive
        // cache（用户设置时），否则回退到全局 disk-cache 设置。
        // HP 工作站型号 (hp-i2000 / hp-zx2000 / hp-zx6000) 只通过
        // IFB (82468GX) / CMD649 IDE 控制器连接板载存储；
        // 其固件从 IDE 启动，-drive if=scsi 会落到固件无法读取的
        // PCI SCSI HBA (isp12160 / lsi53c895a) 上。因此对它们强制 IDE，
        // 无论配置的 per-drive 接口是什么（VPC 型号继续使用 LSI 板载 SCSI）。
        // 注意 hp-zx2000：其 EFI 固件只枚举主 IDE 通道，
        // 因此只有 index=0/1 的介质对它可见 —— 在该机器上从 CD 启动时，
        // 请让 HDA/HDB 空闲（或不使用）。
        final Machine machine = getMachine();
        final boolean isHp = getMachineType() != null && getMachineType().startsWith("hp-");

        String ifaceHda = isHp ? "ide" : resolveDriveInterface(machine.getHdaInterface());
        String ifaceHdb = isHp ? "ide" : resolveDriveInterface(machine.getHdbInterface());
        String ifaceHdc = isHp ? "ide" : resolveDriveInterface(machine.getHdcInterface());
        String ifaceHdd = isHp ? "ide" : resolveDriveInterface(machine.getHddInterface());

        String pathHda = getDriveFilePath(machine.getHdaImagePath());
        String pathHdb = getDriveFilePath(machine.getHdbImagePath());
        String pathHdc = getDriveFilePath(machine.getHdcImagePath());
        String pathHdd = getDriveFilePath(machine.getHddImagePath());

        String fmtHda = resolveDriveFormat(machine.getHdaFormat(), pathHda, true);
        String fmtHdb = resolveDriveFormat(machine.getHdbFormat(), pathHdb, true);
        String fmtHdc = resolveDriveFormat(machine.getHdcFormat(), pathHdc, true);
        String fmtHdd = resolveDriveFormat(machine.getHddFormat(), pathHdd, true);

        String cacheHda = resolveDriveCache(machine.getHdaCache(), globalCache);
        String cacheHdb = resolveDriveCache(machine.getHdbCache(), globalCache);
        String cacheHdc = resolveDriveCache(machine.getHdcCache(), globalCache);
        String cacheHdd = resolveDriveCache(machine.getHddCache(), globalCache);

        addDrive(paramsList, "0", ifaceHda, "disk", null, pathHda, fmtHda, cacheHda);
        addDrive(paramsList, "1", ifaceHdb, "disk", null, pathHdb, fmtHdb, cacheHdb);
        addDrive(paramsList, "2", ifaceHdc, "disk", null, pathHdc, fmtHdc, cacheHdc);
        addDrive(paramsList, "3", ifaceHdd, "disk", null, pathHdd, fmtHdd, cacheHdd);

        // CDROM 使用 machine.getCDInterface()。接口按配置使用
        //（null/空 通过 resolveDriveInterface 回退到 QEMU 默认），
        // 例如 IA-64 启动介质用 "scsi"。
        String cdInterface = isHp ? "ide" : resolveDriveInterface(machine.getCDInterface());
        String cdPath = getDriveFilePath(machine.getCdImagePath());
        addDrive(paramsList, null,
                cdInterface, "cdrom", null,
                cdPath, resolveDriveFormat(machine.getCDFormat(), cdPath, false), null);

        // 软驱 FDA/FDB
        if (Config.enableEmulatedFloppy) {
            addDrive(paramsList, "0", "floppy", null, null,
                    getDriveFilePath(machine.getFdaImagePath()), null, null);
            addDrive(paramsList, "1", "floppy", null, null,
                    getDriveFilePath(machine.getFdbImagePath()), null, null);
        }

        // SD 卡：-drive if=none,id=sd0 与 sd-card 设备配对
        if (Config.enableEmulatedSDCard) {
            String sdImagePath = getDriveFilePath(machine.getSdImagePath());
            if (sdImagePath != null) {
                paramsList.add("-device");
                paramsList.add("sd-card,drive=sd0,bus=sd-bus");
                addDrive(paramsList, null, "none", null, "sd0", sdImagePath, null, null);
            }
        }

        // 共享文件夹作为虚拟 FAT 驱动器挂载
        if (Config.enableSharedFolder) {
            String sharedFolderPath = getDriveFilePath(machine.getSharedFolderPath());
            if (sharedFolderPath != null) {
                addDrive(paramsList, "3", "ide", "disk", null,
                        "fat:rw:" + sharedFolderPath, "raw", null);
            }
        }
    }

    /**
     * 向 paramsList 追加单个 "-drive" 参数。所有存储设备都通过该辅助方法，
     * 以保持 QEMU 命令行统一。
     *
     * @param index 总线索引 ("0".."3")，if=none（SD 卡）时为 null
     * @param iface 接口：ide、scsi、virtio、floppy、none、...
     * @param media 介质类型：disk、cdrom 或 null
     * @param id    drive id（用于 if=none 的驱动器，如 sd0）
     * @param file  镜像文件路径，或共享文件夹的 "fat:rw:<dir>"
     * @param format 强制格式 (raw)，null 表示自动检测
     * @param cache  cache 模式或 null
     */
    private void addDrive(ArrayList<String> paramsList, String index, String iface, String media,
                          String id, String file, String format, String cache) {
        if (file == null || file.trim().isEmpty())
            return;
        StringBuilder param = new StringBuilder(128);
        if (index != null)
            appendDriveField(param, "index", index);
        if (iface != null)
            appendDriveField(param, "if", iface);
        appendDriveField(param, "media", media);
        appendDriveField(param, "id", id);
        appendDriveField(param, "file", file);
        appendDriveField(param, "format", format);
        appendDriveField(param, "cache", cache);
        paramsList.add("-drive");
        paramsList.add(param.toString());
    }

    /** 向 -drive 参数字符串追加一个字段。 */
    private void appendDriveField(StringBuilder param, String field, String value) {
        if (value == null || value.isEmpty())
            return;
        if (param.length() > 0)
            param.append(",");
        param.append(field).append("=").append(value);
    }


    /**
     * 在连接前更改 VNC 密码。
     * 用户也会被提示创建证书。
     *
     * @param vncPassword 要发送给 QEMU 的 VNC 密码
     */
    protected void vncchangepassword(String vncPassword) throws Exception {
        String res = QmpClient.sendCommand(QmpClient.getChangeVncPasswdCommand(vncPassword));
        String desc;
        if (res != null && !res.isEmpty()) {
            JSONObject resObj = new JSONObject(res);
            if (!resObj.equals("") && res.contains("error")) {
                String resInfo = resObj.getString("error");
                if (!resInfo.isEmpty()) {
                    JSONObject resInfoObj = new JSONObject(resInfo);
                    desc = resInfoObj.getString("desc");
                    Log.e(TAG, desc);
                }
            }
        }
    }

    /** 通过 QMP 更换设备介质。 */
    protected String changedev(String dev, String value) {
        String response = QmpClient.sendCommand(QmpClient.getChangeDeviceCommand(dev, value));
        if (Config.debug) {
            Context app = LimboApplication.getInstance();
            String displayDevValue = FileUtils.getFullPathFromDocumentFilePath(value);
            ToastUtils.toastLong(app, Gravity.BOTTOM,
                    app.getString(R.string.ChangedDevice) + ": " + dev + ": " + displayDevValue);
        }
        return response;
    }

    /** 通过 QMP 弹出设备介质。 */
    protected String ejectdev(String dev) {
        String response = QmpClient.sendCommand(QmpClient.getEjectDeviceCommand(dev));
        if (Config.debug) {
            Context app = LimboApplication.getInstance();
            ToastUtils.toastLong(app, Gravity.BOTTOM,
                    app.getString(R.string.EjectedDevice) + ": " + dev);
        }
        return response;
    }


    /**
     * 启动稍后会启动 qemu 进程的服务。
     */
    public void startService() {
        Intent i = new Intent(Config.ACTION_START, null, LimboApplication.getInstance(),
                MachineController.getInstance().getServiceClass());
        Bundle b = new Bundle();
        i.putExtras(b);
        Log.d(TAG, "Starting VM service");
        // API 26+ 对于会调用 startForeground() 的服务（MachineService 会）
        // 要求使用 startForegroundService()。在那里使用 startService()
        // 会受后台服务启动限制影响。
        Context app = LimboApplication.getInstance();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(i);
        } else {
            app.startService(i);
        }
    }

    /**
     * 返回可以用于弹对话框的 Activity。
     *
     * <p>不能用 {@link LimboApplication#getInstance()}（Application Context）弹窗：
     * 应用主题不是 Theme.AppCompat 后代时 {@code MaterialAlertDialogBuilder} 的构造函数
     * 会直接抛出 {@code IllegalArgumentException: The style on this component requires
     * your app theme to be Theme.AppCompat (or a descendant)}；即使主题正确，
     * Application Context 也没有窗口 token，{@code show()} 依旧会抛
     * {@code WindowManager$BadTokenException}。
     *
     * @return 可用的 Activity，没有则返回 null
     */
    @Nullable
    private Activity getDialogActivity() {
        Activity activity = LimboApplication.getCurrentActivity();
        if (activity == null || activity.isFinishing()) {
            return null;
        }
        if (activity.isDestroyed()) {
            return null;
        }
        return activity;
    }

    /**
     * 记录 KVM 启动方式的选择，只会生效一次（避免对话框销毁回调覆盖用户的选择）。
     */
    private void decideKvmRoot(java.util.concurrent.atomic.AtomicBoolean decided,
                               int choice,
                               java.util.concurrent.CountDownLatch latch) {
        if (decided.compareAndSet(false, true)) {
            kvmChoice.set(choice);
            latch.countDown();
        }
    }

    /**
     * 当加速器为 KVM 时询问用户是否以 root 身份启动 VM。
     *
     * <p>该方法会阻塞调用线程（后台线程），直到用户在对话框上做出选择。
     * 对话框本身在主线程弹出。
     *
     * @return true 表示用户选择以 root 启动
     */
    private boolean askKvmRootChoice() {
        // 已经问过（例如重启）就直接复用上次结果
        int cached = kvmChoice.get();
        if (cached != KVM_CHOICE_UNDECIDED) {
            return cached == KVM_CHOICE_ROOT;
        }

        synchronized (kvmChoiceLock) {
            if (kvmChoice.get() == KVM_CHOICE_UNDECIDED) {
                final java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(1);
                final java.util.concurrent.atomic.AtomicBoolean decided =
                        new java.util.concurrent.atomic.AtomicBoolean(false);

                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    final Activity activity = getDialogActivity();
                    if (activity == null) {
                        // 没有 Activity 时无法弹窗，直接按普通方式启动
                        Log.w(TAG, "No Activity available, skipping KVM root dialog");
                        decideKvmRoot(decided, KVM_CHOICE_NORMAL, latch);
                        return;
                    }
                    try {
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.kvm_root_dialog_title)
                                .setMessage(R.string.kvm_root_dialog_message)
                                .setCancelable(false)
                                .setPositiveButton(R.string.kvm_root_dialog_root,
                                        (d, w) -> decideKvmRoot(decided, KVM_CHOICE_ROOT, latch))
                                .setNegativeButton(R.string.kvm_root_dialog_normal,
                                        (d, w) -> decideKvmRoot(decided, KVM_CHOICE_NORMAL, latch))
                                // 例如 Activity 被销毁导致对话框消失时兜底，
                                // 否则 VM 启动线程会一直阻塞在 latch 上
                                .setOnDismissListener(d ->
                                        decideKvmRoot(decided, KVM_CHOICE_NORMAL, latch))
                                .show();
                    } catch (Throwable t) {
                        // 弹窗失败（如无 Activity）时回退到普通启动
                        Log.e(TAG, "Failed to show KVM root dialog", t);
                        decideKvmRoot(decided, KVM_CHOICE_NORMAL, latch);
                    }
                });

                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    kvmChoice.set(KVM_CHOICE_NORMAL);
                }
            }
        }
        return kvmChoice.get() == KVM_CHOICE_ROOT;
    }
    /**
     * 启动原生进程。应从后台线程的前台服务中调用，以防止进程被杀。
     *
     * @return 来自原生代码 vm-executor-jni.cpp 的字符串
     */
    public String start() {
        String res = null;
        try {
            String[] params = prepareParams(LimboApplication.getInstance());
            printParams(params);

            // gunyah/gzvm 会打开 /dev/gunyah 或 /dev/gzvm，在目标设备上
            // 这是 root-only 的：除非当前进程已经是 root，
            // 否则在 root 子进程中运行 VM（通过 app_process 启动独立 JVM）。
            // KVM 同样可能因为 /dev/kvm 权限问题需要 root，此时询问用户。
            String accelMode = getMachine().getAccelMode();
            boolean needsRootAccel = Machine.ACCEL_GUNYAH.equals(accelMode)
                    || Machine.ACCEL_GZVM.equals(accelMode);
            if (needsRootAccel && !RootUtils.isRoot()) {
                return startRootProcess(params);
            }

            // KVM：询问用户是否以 root 启动（仅当当前不是 root 时才有意义）
            boolean isKvm = Machine.ACCEL_KVM.equals(accelMode);
            if (isKvm && !RootUtils.isRoot()) {
                boolean useRoot = askKvmRootChoice();
                if (useRoot) {
                    return startRootProcess(params);
                }
                // 用户选择普通启动，继续走进程内路径
            }

            // XXX: 对于 VNC，我们需要在合理时间后手动恢复
            if (getMachine().getPaused() == 1 && MachineController.getInstance().isVNCEnabled()) {
                continueVM(5000);
            }

            if (MachineController.getInstance().isVNCEnabled()
                    && LimboSettingsManager.getVNCEnablePassword(LimboApplication.getInstance())) {
                changeVncPass(LimboApplication.getInstance(), 2000);
            }

            QmpClient.setExternal(LimboSettingsManager.getEnableExternalQMP(LimboApplication.getInstance()));
            // 在 VM 启动时读取，使设置对当前运行生效。
            String libFilename = getQemuLibrary();
            res = start(Config.storagedir, LimboApplication.getBasefileDir(),
                    libFilename,
                    FileUtils.getNativeLibSearchDir(LimboApplication.getInstance()) + "/" + libFilename,
                    params);
        } catch (Exception ex) {
            Log.e(TAG, "VM start failed", ex);
            ToastUtils.toastLong(LimboApplication.getInstance(), String.valueOf(ex.getMessage()));
            return res;
        }
        return res;
    }

    /**
     * 测试入口（{@code com.limbo.emu.debug.QemuCommandTestActivity} 使用）：不走
     * {@link #prepareParams(Context)} 的参数拼装，直接把给定参数交给原生 QEMU 引导流程
     * （loadLib + qemu_init + qemu_main_loop + qemu_cleanup）。
     *
     * <p>必须在非主线程调用，调用期间会一直阻塞到 QEMU 退出。
     *
     * @param params      原样交给 QEMU 的参数数组，不会追加任何参数
     * @param libFilename 需要 dlopen 的 QEMU 库文件名（如 libqemu-system-x86_64.so）
     * @return 原生层返回的结果字符串
     */
    String startRaw(@NonNull String[] params, @NonNull String libFilename) {
        String libPath = FileUtils.getNativeLibSearchDir(LimboApplication.getInstance())
                + "/" + libFilename;
        return start(Config.storagedir, LimboApplication.getBasefileDir(),
                libFilename, libPath, params);
    }

    /** 测试入口：请求终止由 {@link #startRaw(String[], String)} 启动的 QEMU。 */
    String stopRaw(int restart) {
        return stop(restart);
    }

    /**
     * 测试入口：取当前进程的 {@link VMExecutor} 实例。
     *
     * <p>优先复用 {@link MachineController} 已经创建的实例；若还没有创建过，
     * MachineController 的构造过程会创建它自己的 VMExecutor，这里直接取该实例，
     * 避免出现第二个实例。
     */
    static VMExecutor obtain() {
        if (mInstance == null) {
            MachineController.getInstance();
        }
        return mInstance;
    }

    /**
     * 在 root 子进程中启动 VM：su + app_process 运行独立 JVM
     * （RootVmLauncher）来加载 qemu 库。阻塞直到子进程退出，
     * 这样 MachineService 生命周期与进程内路径保持一致。
     */
    private String startRootProcess(String[] params) {
        final Context ctx = LimboApplication.getInstance();
        if (isRootVmRunning()) {
            return ctx.getString(R.string.root_vm_already_running);
        }
        try {
            File filesDir   = ctx.getFilesDir();
            File scriptFile = new File(filesDir, ROOT_VM_SCRIPT);
            File pidFile    = new File(filesDir, ROOT_VM_PID);
            File statusFile = new File(filesDir, RootVmLauncher.STATUS_FILENAME);
            File errFile    = new File(filesDir, ROOT_VM_STDERR);

            // 关闭安装时解压时 nativeLibraryDir 为空，getNativeLibSearchDir 会返回
            // APK 内的 lib/<abi> 路径（<apk>!/lib/<abi>），子进程可直接从 APK 加载。
            String nativeLibDir = FileUtils.getNativeLibSearchDir(ctx);
            String apkPath = ctx.getApplicationInfo().sourceDir;
            if (apkPath == null || !new File(apkPath).exists()) {
                return startFailed("APK path unavailable: " + apkPath);
            }
            String appProcess = new File("/system/bin/app_process64").exists()
                    ? "/system/bin/app_process64" : "/system/bin/app_process";
            if (!new File(appProcess).exists()) {
                return startFailed("app_process not found");
            }

            String libFilename = params[0];
            String libPath     = nativeLibDir + "/" + libFilename;
            String[] childParams = headlessParams(params);

            writeRootVmScript(scriptFile, nativeLibDir, apkPath, appProcess,
                    filesDir, pidFile, libFilename, libPath, childParams);

            deleteQuietly(pidFile);
            deleteQuietly(statusFile);
            deleteQuietly(errFile);

            launchSuProcess(scriptFile, errFile);

            if (!awaitRootVmStart(pidFile, errFile)) {
                return startFailed(rootVmPid == null
                        ? "no pid after " + ROOT_VM_START_TIMEOUT_MS + "ms. " + readTail(errFile)
                        : "su exited early. " + readTail(errFile));
            }

            Log.d(TAG, "Root VM running with pid " + rootVmPid);
            awaitRootVmExit();
            rootVmMode = false;
            rootVmProcess = null;

            return parseRootVmStatus(statusFile, errFile);
        } catch (IOException e) {
            Log.e(TAG, "Failed to launch root VM", e);
            if (!RootUtils.hasSu()) {
                return ctx.getString(R.string.root_vm_no_su);
            }
            return startFailed(e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch root VM", e);
            return startFailed(e.getMessage());
        }
    }

    /** 构造 root VM 启动失败时的提示字符串。 */
    @NonNull private String startFailed(String detail) {
        return LimboApplication.getInstance().getString(R.string.root_vm_start_failed)
                + (detail == null || detail.isEmpty() ? "" : ": " + detail);
    }

    /** 生成 root VM 启动脚本。 */
    private void writeRootVmScript(File scriptFile, String nativeLibDir, String apkPath,
                                   String appProcess, @NonNull File filesDir, @NonNull File pidFile,
                                   String libFilename, String libPath,
                                   @NonNull String[] childParams) throws IOException {
        StringBuilder sb = new StringBuilder(512);
        sb.append("#!/system/bin/sh\n");
        sb.append("export LD_LIBRARY_PATH=").append(shq(nativeLibDir)).append("\n");
        sb.append("export CLASSPATH=").append(shq(apkPath)).append("\n");
        sb.append("echo $$ > ").append(shq(pidFile.getAbsolutePath())).append("\n");
        // 新版 app_process 只认命令行 vm 选项，不再读取 CLASSPATH 环境变量：
        // 必须在“父目录”参数之前用 -cp 传类路径，否则子进程 boot classpath 里没有
        // 应用 dex，FindClass("com/limbo/emu/jni/RootVmLauncher") 返回 null，留下的
        // pending ClassNotFoundException 会让 startReg() 里的 AssertNoPendingException
        // 直接 abort。上面的 export CLASSPATH 仅对老版本 app_process 有效，保留无副作用。
        sb.append("exec ").append(appProcess).append(" -cp ").append(shq(apkPath))
                .append(" /system/bin com.limbo.emu.jni.RootVmLauncher");
        sb.append(' ').append(shq(nativeLibDir));
        sb.append(' ').append(shq(filesDir.getAbsolutePath()));
        sb.append(' ').append(shq(libFilename));
        sb.append(' ').append(shq(libPath));
        sb.append(' ').append(shq(Config.storagedir));
        sb.append(' ').append(shq(LimboApplication.getBasefileDir()));
        for (String p : childParams) {
            sb.append(' ').append(shq(p));
        }
        sb.append('\n');

        try (FileOutputStream fos = new FileOutputStream(scriptFile, false)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 通过 su 启动脚本进程，并排空其输出到日志文件。 */
    private void launchSuProcess(@NonNull File scriptFile, File errFile) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "su", "-c", "sh " + shq(scriptFile.getAbsolutePath()));
        pb.redirectErrorStream(true);
        rootVmProcess = pb.start();
        drainRootVmLog(rootVmProcess, errFile);
    }

    /** 等待 root VM 启动（pid 文件出现）。 */
    private boolean awaitRootVmStart(File pidFile, File errFile) {
        long deadline = System.currentTimeMillis() + ROOT_VM_START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && rootVmPid == null) {
            // 先看 pid 文件：部分 su 实现 fork 出命令后自己立刻退出，此时不能
            // 因为 su 进程结束就判定启动失败。
            String pid = readPid(pidFile);
            if (pid != null) {
                rootVmPid = pid;
                rootVmMode = true;
                return true;
            }
            if (!isProcessAlive(rootVmProcess)) {
                return false;
            }
            try {
                Thread.sleep(ROOT_VM_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return rootVmPid != null;
    }

    /** 阻塞等待 root VM 退出。 */
    private void awaitRootVmExit() {
        while (isRootVmAlive()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 解析 root VM 退出状态文件。
     *
     * <p>只有两种情况算"正常关机"：子进程自己写了 {@code stopped} 状态（用户关机后
     * QEMU 正常退出），或者关机是父进程发起的（父进程先杀子进程，子进程被信号终结、
     * 来不及写状态）。
     *
     * <p>其余情况（状态文件停留在 {@code starting}，或者根本没有状态文件）都说明
     * root 子进程是被信号杀死或崩溃退出的，必须报错并带上子进程日志末尾；否则会被
     * 当成"用户关机"处理，表现就是"开机即关机"而且没有任何提示。
     */
    private String parseRootVmStatus(File statusFile, File errFile) {
        boolean stopRequested = rootVmStopRequested;
        rootVmStopRequested = false;
        String status = readText(statusFile);
        if (status != null && status.startsWith(RootVmLauncher.STATUS_PREFIX_ERROR)) {
            return startFailed(status.substring(RootVmLauncher.STATUS_PREFIX_ERROR.length()).trim()
                    + tailOf(errFile));
        }
        if (status != null && status.startsWith(RootVmLauncher.STATUS_PREFIX_STOPPED)) {
            String res = status.substring(RootVmLauncher.STATUS_PREFIX_STOPPED.length()).trim();
            if (!res.isEmpty() && !VM_SHUTDOWN.equals(res)) {
                return res;
            }
            return VM_SHUTDOWN;
        }
        if (stopRequested) {
            // 用户主动关机：状态文件不会更新，属于预期内的正常退出。
            return VM_SHUTDOWN;
        }
        String detail = status == null ? "no status file" : status;
        return LimboApplication.getInstance().getString(R.string.root_vm_exited_unexpectedly)
                + ": " + detail + tailOf(errFile);
    }

    /** 读取 root 子进程日志末尾，拼成错误提示的一部分。 */
    @NonNull private static String tailOf(File f) {
        String tail = readTail(f);
        return tail.isEmpty() ? "" : "\n" + tail;
    }

    // SDL/GTK 窗口只存在于 app 进程中；root 子进程必须以 headless 运行。
    // 当配置了 VNC 时，VNC 仍由子进程提供服务。
    private static String[] headlessParams(@NonNull String[] params) {
        boolean hasVnc = false;
        int displayIdx = -1;
        for (int i = 0; i < params.length; i++) {
            String p = params[i];
            if ("-vnc".equals(p)) {
                hasVnc = true;
            } else if ("-display".equals(p) && i + 1 < params.length) {
                displayIdx = i;
            }
        }
        if (displayIdx >= 0) {
            String[] out = params.clone();
            out[displayIdx + 1] = "none";
            return out;
        }
        if (hasVnc) {
            return params;
        }
        String[] out = Arrays.copyOf(params, params.length + 2);
        out[params.length]     = "-display";
        out[params.length + 1] = "none";
        return out;
    }

    /** shell 单引号转义。 */
    @NonNull private static String shq(@NonNull String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 判断 Process 是否存活。 */
    private static boolean isProcessAlive(Process p) {
        if (p == null) {
            return false;
        }
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /**
     * 判断 root VM 是否存活。
     *
     * <p>不能用 {@code /proc/<pid>} 判断：Android 7 起 /proc 以 hidepid=2 挂载，
     * 应用看不到其它 uid 的进程目录，root 子进程的 /proc 项对 App 不可见。用它判断
     * 会把"正在运行"当成"已退出"，于是 root VM 刚启动就被当成正常关机、App 立刻退出
     * （表现就是"开机即关机"）。
     *
     * <p>因此以 su 进程为准：su 是 root 子进程的父进程，子 VM 退出后 su 才退出。
     * 若某些 su 实现 fork 出命令后自己先退出，再用 root 权限的 {@code kill -0} 兜底确认。
     */
    private boolean isRootVmAlive() {
        if (isProcessAlive(rootVmProcess)) {
            return true;
        }
        return isPidAliveAsRoot(rootVmPid);
    }

    /** 用 root 权限确认 pid 是否存活（App 看不到 root 进程的 /proc 项，只能问 root）。 */
    private static boolean isPidAliveAsRoot(@Nullable String pid) {
        if (pid == null || pid.trim().isEmpty()) {
            return false;
        }
        try {
            Process p = new ProcessBuilder("su", "-c", "kill -0 " + pid)
                    .redirectErrorStream(true).start();
            long deadline = System.currentTimeMillis() + ROOT_VM_POLL_MS;
            while (System.currentTimeMillis() < deadline && isProcessAlive(p)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (isProcessAlive(p)) {
                // su 卡住（例如等待授权弹窗）：保守地当作子进程还活着
                p.destroy();
                return true;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            Log.w(TAG, "Could not check root VM pid " + pid, e);
        }
        return false;
    }

    /**
     * 判断 root VM 是否正在运行。
     *
     * <p>以进程内状态为准：App 看不到 root 子进程的 /proc 项，用"pid 文件 + /proc"
     * 判断永远会得到"没在运行"，也就无法识别重复启动。
     */
    private boolean isRootVmRunning() {
        return rootVmMode && isRootVmAlive();
    }

    /** 从 pid 文件读取 pid。 */
    @Nullable private static String readPid(File pidFile) {
        String pid = readText(pidFile);
        if (pid == null) {
            return null;
        }
        String trimmed = pid.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 读取文本文件内容。 */
    private static String readText(File f) {
        if (f == null || !f.exists()) {
            return null;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(256);
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() >= MAX_READ_CHARS) {
                    break;
                }
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return null;
        }
    }

    /** 读取文件末尾若干行，用于错误提示。 */
    private static String readTail(File f) {
        String text = readText(f);
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] lines = text.split("\n");
        int start = Math.max(0, lines.length - 5);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().trim();
    }

    /** 静默删除文件。 */
    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // 将子进程的 stdout/stderr 转发到日志文件，以便启动失败
    //（缺少库、SELinux 拒绝等）仍然可诊断。
    private void drainRootVmLog(final Process p, final File errFile) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 FileOutputStream out = new FileOutputStream(errFile, true)) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    Log.d(TAG, "[root-vm] " + line);
                }
            } catch (Throwable th) {
                Log.w(TAG, "drainRootVmLog stopped", th);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** 停止 root 子进程；restart 非 0 时改为复位（与进程内路径一致）。 */
    private void stopRootProcess(int restart) {
        if (restart != 0) {
            // 进程内路径用 QMP reset 让 QEMU 原地复位。root 模式下不能"杀掉子进程再起
            // 一个新的"：那会让仍在等 VM 退出的 start() 直接返回，App 会把复位当成关机
            // 并结束自己，于是复位一次就等于关机。
            QmpClient.sendCommand(QmpClient.getResetCommand());
            return;
        }
        // 记下这次退出是父进程主动要求的结果：子进程收到信号就结束了，来不及写
        // stopped 状态，不能把它当成"异常退出"来报错。
        rootVmStopRequested = true;
        killRootVm("TERM");
        long deadline = System.currentTimeMillis() + ROOT_VM_STOP_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && isRootVmAlive()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (isRootVmAlive()) {
            killRootVm("KILL");
        }
        if (rootVmProcess != null) {
            try {
                rootVmProcess.destroy();
            } catch (Throwable ignored) {
            }
        }
        rootVmPid = null;
        rootVmMode = false;
    }

    /** 向 root VM 发送信号。 */
    private void killRootVm(String signal) {
        if (rootVmPid == null) {
            return;
        }
        try {
            Process p = new ProcessBuilder("su", "-c", "kill -" + signal + " " + rootVmPid)
                    .redirectErrorStream(true).start();
            p.waitFor();
        } catch (IOException e) {
            Log.e(TAG, "Could not signal root VM (" + signal + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 延迟更改 VNC 密码。 */
    private void changeVncPass(final Context context, final long delay) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    vncchangepassword(LimboSettingsManager.getVNCPass(context));
                } catch (Exception e) {
                    ToastUtils.toastLong(LimboApplication.getInstance(),
                            context.getString(R.string.CouldNotSetVNCPass) + ": " + e.getMessage());
                    Log.e(TAG, "Failed to change VNC password", e);
                }
            }
        }).start();
    }

    /** 延迟继续 VM。 */
    private void continueVM(final int delay) {
        // TODO: We shouldn't have to go through the view dispatcher
        LimboApplication.getViewListener().onAction(MachineAction.CONTINUE_VM, delay);
    }

    /** 停止 VM，restart 非 0 时重启。 */
    public void stopvm(final int restart) {
        new Thread(() -> {
            if (rootVmMode) {
                stopRootProcess(restart);
                return;
            }
            if (restart != 0) {
                QmpClient.sendCommand(QmpClient.getResetCommand());
            } else {
                //XXX: Qmp command only halts the VM but doesn't exit so we use force close
                // QmpClient.sendCommand(QmpClient.powerDown());
                stop(restart);
            }
        }).start();
    }

    @Override
    public int getSdlRefreshRate(boolean idle) {
        return idle ? getSDLRefreshRateIdle() : getSDLRefreshRateDefault();
    }

    @Override
    public void setSdlRefreshRate(int value, boolean idle) {
        if (idle) {
            setSDLRefreshRateIdle(value);
        } else {
            setSDLRefreshRateDefault(value);
        }
    }

    @Override
    public String getDeviceName(@NonNull MachineProperty driveProperty) {
        // 在 IA-64 上，CD-ROM 位于 LSI SCSI 总线（unit 4，见 addDrives()），
        // 因此它的 QMP id 是 "scsi0-cd4"，而不是其他架构使用的旧式 "ide1-cd0"。
        if (driveProperty == MachineProperty.CDROM) {
            if (LimboApplication.arch == Config.Arch.ia64
                    || LimboApplication.arch == Config.Arch.ia64w) {
                // IA-64 VPC 型号把 CD-ROM 挂在板载 LSI SCSI 上
                //（unit 4 -> "scsi0-cd4"）。HP 工作站型号则通过
                // IFB/CMD649 IDE 控制器连接存储，因此它们的 CD 是普通
                // IDE CD 设备（尽力而为的名称；通道/单元取决于运行时
                // 4 单元 IDE 总线上有多少硬盘）。
                if (getMachineType() != null && getMachineType().startsWith("hp-")) {
                    return cdDeviceName;
                }
                return "scsi0-cd4";
            }
            return cdDeviceName;
        }
        switch (driveProperty) {
            case FDA:
                return fdaDeviceName;
            case FDB:
                return fdbDeviceName;
            case SD:
                return sdDeviceName;
            default:
                return null;
        }
    }

    @Override
    public synchronized void updateDisplay(int width, int height, int orientation) {
        if (!LimboSettingsManager.getPreventMouseOutOfBounds(LimboApplication.getInstance())) {
            return;
        }
        String mouse = getMachine().getMouse();
        // 如果 guest os 使用绝对指针设备（usb-tablet、virtio-tablet-pci），
        // 我们需要防止鼠标移出边界。当使用触控板且 guest 显示无法
        // 适配 Android Surface 时就会发生这种情况，而这几乎是常态。
        // 我们可以用 SurfaceHolder.setFixedSize() 来限制 surfaceview，
        // 但这会带来刷新 surfaceview 的问题，而且对于触控板我们仍然需要
        // 这个修复。
        // 注意：持久化的值可能带有 "(Fixes Mouse)" spinner 后缀，因此用 startsWith()
        if (mouse != null
                && (mouse.startsWith("usb-tablet") || mouse.startsWith("virtio-tablet-pci"))
                && vm_width > 0 && vm_height > 0) {
            // 计算带黑边的（保持宽高比的）显示区域，方式与
            // QEMU SDL 后端相同（scale = MIN(w/vm_w, h/vm_h)，居中），
            // 这样鼠标边界始终与屏幕上的 guest 图像匹配。
            double scale = Math.min((double) width / vm_width, (double) height / vm_height);
            double dispW = vm_width * scale;
            double dispH = vm_height * scale;
            int xmin = (int) Math.round((width - dispW) / 2.0);
            int xmax = (int) Math.round((width + dispW) / 2.0);
            int ymin = (int) Math.round((height - dispH) / 2.0);
            int ymax = (int) Math.round((height + dispH) / 2.0);
            nativeMouseBounds(xmin, xmax, ymin, ymax);
        }
    }

    @Override
    public void setFullscreen() {
        nativeFullscreen();
        //TODO: sparc 没有 vga，因此我们需要看看是否能对 cg3 做类似调用
        if (LimboApplication.arch == Config.Arch.x86
                || LimboApplication.arch == Config.Arch.x86_64
                || LimboApplication.arch == Config.Arch.arm
                || LimboApplication.arch == Config.Arch.arm64
                || LimboApplication.arch == Config.Arch.ia64
                || LimboApplication.arch == Config.Arch.ia64w) {
            nativeRefreshScreen(1);
        }
    }

    @Override
    public void enableAaudio(int value) {
        nativeEnableAaudio(value, Config.aaudioLibName,
                FileUtils.getNativeLibSearchDir(LimboApplication.getInstance())
                        + "/" + Config.aaudioLibName);
    }

    //TODO: re-enable getting status from the vm
    /** 通过 QMP 获取 VM 状态。 */
    public String getVmState() {
        String res = QmpClient.sendCommand(QmpClient.getStateCommand());
        String state = "";
        if (res != null && !res.isEmpty()) {
            try {
                JSONObject resObj = new JSONObject(res);
                String resInfo = resObj.getString("return");
                JSONObject resInfoObj = new JSONObject(resInfo);
                state = resInfoObj.getString("status");
            } catch (JSONException e) {
                Log.e(TAG, "Failed to parse VM state", e);
            }
        }
        return state;
    }

    /**
     * 通过 QMP 更换或弹出可移动设备。
     *
     * @param drive     要更换的设备
     * @param imagePath 如果为 null 则弹出驱动器，否则使用该路径的磁盘文件
     */
    public boolean changeRemovableDevice(final MachineProperty drive, final String imagePath) {
        Context app = LimboApplication.getInstance();
        if (!LimboSettingsManager.getEnableQmp(app)) {
            ToastUtils.toastShort(app, app.getString(R.string.EnableQMPForChangingDrives));
            return false;
        }
        String dev = getDeviceName(drive);

        //XXX: 首先弹出之前的介质
        ejectdev(dev);

        // 如果没有介质，就没有其他事可做
        if (imagePath == null || imagePath.trim().isEmpty()) {
            return true;
        }

        //XXX: 我们编码 document file path 中的一些字符，
        // 以便 qemu 正确处理
        String imagePathConverted = FileUtils.encodeDocumentFilePath(imagePath);

        if (!FileUtils.fileValid(imagePathConverted)) {
            String msg = app.getString(R.string.CouldNotOpenDocFile) + " "
                    + FileUtils.getFullPathFromDocumentFilePath(imagePathConverted)
                    + "\n" + app.getString(R.string.PleaseReassingYourDiskFiles);
            ToastUtils.toastLong(app, msg);
            return false;
        }
        String response = changedev(dev, imagePathConverted);
        return response != null;
    }

    /**
     * 该函数是从原生代码调用的 c get_fd() 函数的透传。
     * 桥接到 Java 代码，因为这是从原生代码打开文件描述符的唯一方式。
     *
     * @param path 文件路径
     * @return FileUtils.get_fd() 的返回值
     */
    public int get_fd(String path) {
        return FileUtils.get_fd(path);
    }

    /**
     * 该函数是从原生代码调用的 c close_fd() 函数的透传。
     * 与上面的 get_fd 类似，但可能不需要。
     *
     * @param fd 要关闭的文件描述符
     * @return FileUtils.close_fd() 的返回值
     */
    public int close_fd(int fd) {
        return FileUtils.close_fd(fd);
    }

    @Override
    public String saveVM() {

        // 删除之前的任何状态文件
        File file = new File(getSaveStateName());
        if (file.exists()) {
            if (!file.delete()) {
                return LimboApplication.getInstance().getString(R.string.CannotDeletePreviousStateFile);
            }
        }

        if (Config.showToast)
            ToastUtils.toastShort(LimboApplication.getInstance(),
                    LimboApplication.getInstance().getString(R.string.PleaseWaitSavingVMState));

        // QEMU 10.x 不再从 QMP monitor 解析数字 "fd:" migration URI
        //（monitor_get_fd 只能找到通过 getfd 命令注册的命名 fd），
        // 因此使用 "file:" 方案，它直接打开状态文件路径。
        String uri = "file:" + getSaveStateName();
        QmpClient.sendCommand(QmpClient.getStopVMCommand());
        String command = QmpClient.getMigrateCommand(false, false, uri);
        String msg = QmpClient.sendCommand(command);
        if (msg != null) {
            return processMigrationResponse(msg);
        }
        return null;
    }

    @Override
    public void continueVM() {
        QmpClient.sendCommand(QmpClient.getContinueVMCommand());
    }

    @Override
    public MachineController.MachineStatus getSaveVMStatus() {
        String pauseState = "";
        String res = QmpClient.sendCommand(QmpClient.getQueryMigrationCommand());

        if (res != null && !res.isEmpty()) {
            try {
                JSONObject resObj = new JSONObject(res);
                String resInfo = resObj.getString("return");
                JSONObject resInfoObj = new JSONObject(resInfo);
                // 没有正在进行的迁移时，QEMU 会省略 "status" 成员
                //（状态 MIGRATION_STATUS_NONE）；不要因此抛异常，
                // 让 pauseState 保持空，以便轮询可以重试。
                if (resInfoObj.has("status"))
                    pauseState = resInfoObj.getString("status");
            } catch (JSONException e) {
                if (Config.debug)
                    Log.e(TAG, "Error while checking saving vm: " + e.getMessage());
            }
            if ("FAILED".equals(pauseState.toUpperCase(Locale.US))) {
                Log.e(TAG, "Error: " + res);
            }
        }
        String state = pauseState.toUpperCase(Locale.US);
        switch (state) {
            case "ACTIVE":
            case "SETUP":
                return MachineController.MachineStatus.Saving;
            case "COMPLETED":
                return MachineController.MachineStatus.SaveCompleted;
            case "FAILED":
            case "CANCELLED":
                return MachineController.MachineStatus.SaveFailed;
        }
        //TODO: proper error handling with user messages
        return MachineController.MachineStatus.Unknown;
    }

    /** 处理迁移响应，提取错误描述。 */
    private String processMigrationResponse(String response) {
        String errorStr = null;
        try {
            JSONObject object = new JSONObject(response);
            errorStr = object.getString("error");
        } catch (Exception ex) {
            if (Config.debug)
                ex.printStackTrace();
        }
        if (errorStr != null) {
            String descStr = null;

            try {
                JSONObject descObj = new JSONObject(errorStr);
                descStr = descObj.getString("desc");
            } catch (Exception ex) {
                if (Config.debug)
                    ex.printStackTrace();
            }
            return descStr;
        }
        return null;
    }

    /** 发送鼠标事件。 */
    public void sendMouseEvent(int button, int action, int relative, float x, float y) {
        //XXX: 确保鼠标移动在调整大小时不会触发 SDL 崩溃
        if (LimboSDLActivity.isResizing) {
            return;
        }

        nativeMouseEvent(button, action, relative, (int) x, (int) y);
    }

    /** 是否允许外部 QMP 连接。 */
    public boolean getQMPAllowExternal() {
        return LimboSettingsManager.getEnableExternalQMP(LimboApplication.getInstance());
    }
}