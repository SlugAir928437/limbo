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
package com.max2idea.android.limbo.jni;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import com.limbo.emu.lib.R;
import com.max2idea.android.limbo.files.FileUtils;
import com.max2idea.android.limbo.machine.MachineAction;
import com.max2idea.android.limbo.machine.MachineController;
import com.max2idea.android.limbo.machine.MachineExecutor;
import com.max2idea.android.limbo.machine.MachineProperty;
import com.max2idea.android.limbo.main.Config;
import com.max2idea.android.limbo.main.LimboApplication;
import com.max2idea.android.limbo.main.LimboSDLActivity;
import com.max2idea.android.limbo.main.LimboSettingsManager;
import com.max2idea.android.limbo.qmp.QmpClient;
import com.max2idea.android.limbo.toast.ToastUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Class is used to start and stop the qemu process and communicate file descriptions, mouse,
 * and keyboard events.
 */
class VMExecutor extends MachineExecutor {
    private static final String TAG = "VMExecutor";

    private static final String cdDeviceName = "ide1-cd0";
    private static final String fdaDeviceName = "floppy0";
    private static final String fdbDeviceName = "floppy1";
    private static final String sdDeviceName = "sd0";
    private static int vm_width;
    private static int vm_height;
    //TODO: make this a proper singleton but the views should not be able to access it
    private static VMExecutor mInstance;

    VMExecutor(MachineController machineController) {
        super(machineController);
        mInstance = this;
    }

    /**
     * This function is called when the machine resolution changes. This is called from SDL compat
     * extensions, see folder jni/compat/sdl-extensions
     *
     * @param width  Width
     * @param height Height
     */
    @Keep public static void onVMResolutionChanged(int width, int height) {
        vm_width = width;
        vm_height = height;
        mInstance.onResolutionChanged(vm_width, vm_height);
    }

    //JNI Methods
    private native String start(String storage_dir, String base_dir,
                                String lib_filename, String lib_path,
                                int sdl_scale_hint, Object[] params);

    private native String stop(int restart);

    public native void setSDLRefreshRateDefault(int value);

    public native void setSDLRefreshRateIdle(int value);

    public native int getSDLRefreshRateDefault();

    public native int getSDLRefreshRateIdle();

    public native void nativeIgnoreBreakpointInvalidate(int value);

    public native void nativeMouseEvent(int button, int action, int relative, int x, int y);

    public native void nativeMouseBounds(int xmin, int xmax, int ymin, int ymax);

    public native void nativeFullscreen();

    public native void nativeRefreshScreen(int value);

    public native void nativeEnableAaudio(int value, String aaudioLibName, String aaudioLibPath);

    /**
     * Prints parameters in qemu format
     *
     * @param params Parameters to be printed
     */
    public void printParams(@NonNull String[] params) {
        Log.d(TAG, "Params:");
        for (int i = 0; i < params.length; i++) {
            Log.d(TAG, i + ": " + params[i]);
        }
    }

    // Translate to QEMU format
    private String getSoundCard() {
        if (Config.enableSDLSound && getMachine().getSoundCard() != null
                && !getMachine().getSoundCard().equalsIgnoreCase("none"))
            return getMachine().getSoundCard();
        return null;
    }

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

    @NonNull
    private String getSaveStateName() {
        String machineSaveDirectory = MachineController.getInstance().getMachineSaveDir();
        return machineSaveDirectory + "/" + Config.stateFilename;
    }

    private String[] prepareParams(Context context) throws Exception {
        ArrayList<String> paramsList = new ArrayList<>();
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
     * Adds the vm state file description to the qemu parameters for resuming the vm
     *
     * @param paramsList Existing parameter list to be passed to qemu
     */
    private void addStateOptions(ArrayList<String> paramsList) {
        if (MachineController.getInstance().isPaused() && !getSaveStateName().isEmpty()) {
            // Use the "file:" scheme for -incoming so QEMU opens and owns the
            // state file itself. Passing "fd:N" makes QEMU close the fd when
            // the incoming migration finishes, which trips Android's fdsan
            // (SIGABRT) because the fd is owned by a ParcelFileDescriptor
            // opened by FileUtils.get_fd().
            paramsList.add("-incoming");
            paramsList.add("file:" + getSaveStateName());
        }
    }

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
                // Allow connections only from localhost using localsocket without
                // a password
                vncParam += Config.defaultVNCHost + ":" + Config.defaultVNCPort;
            }
            if (LimboSettingsManager.getVNCEnablePassword(context))
                vncParam += ",password";

            paramsList.add(vncParam);

            //Allow monitor console though it's only supported for VNC, SDL for android doesn't support
            // more than 1 window
            paramsList.add("-monitor");
            paramsList.add("vc");

        } else {
            // gtk 允许多窗口
            if(!gtk) {
                // Expose monitor/serial/parallel over TCP (server,nowait) so the nc
                // module can connect and view the consoles. Raw tcp (not telnet) does
                // not open an SDL window, avoiding the multi-window SDL limitation.
                paramsList.add("-monitor");
                paramsList.add("tcp:127.0.0.1:" + Config.monitorPort + ",server,nowait");

                paramsList.add("-serial");
                paramsList.add("tcp:127.0.0.1:" + Config.serialPort + ",server,nowait");

                paramsList.add("-parallel");
                paramsList.add("tcp:127.0.0.1:" + Config.parallelPort + ",server,nowait");
            }
            paramsList.add("-display");
            if (gtk) {
                // GTK4 android backend (initialized by LimboGtk on the activity side)
                paramsList.add("gtk");
            } else {
                paramsList.add("sdl");
            }
        }

        if (getMachine().getKeyboard() != null) {
            paramsList.add("-k");
            paramsList.add(getMachine().getKeyboard());
        }

        if (getMachine().getMouse() != null && !getMachine().getMouse().equals("ps2")) {
            paramsList.add("-usb");
            paramsList.add("-device");
            paramsList.add(getMachine().getMouse());
            // 对于 ia64 架构的虚拟机，需要添加 usb-kbd 设备以支持键鼠
            // 在i8042=off的情况下无需添加此设备（在 QEMU 中自动添加）
            // FIXME: 在没有控制台的情况下支持 usb-kbd
//            if (LimboApplication.arch == Config.Arch.ia64 || LimboApplication.arch == Config.Arch.ia64w) {
//                paramsList.add("-device");
//                paramsList.add("usb-kbd");
//            }
        }
    }

    private void addAdvancedOptions(ArrayList<String> paramsList) {
        if (getMachine().getExtraParams() != null && !getMachine().getExtraParams().trim().isEmpty()) {
            String[] paramsTmp = getMachine().getExtraParams().split(" ");
            paramsList.addAll(Arrays.asList(paramsTmp));
        }
    }

    private void addAudioOptions(ArrayList<String> paramsList) {
        if (getSoundCard() != null) {
            paramsList.add("-device");
            paramsList.add(getSoundCard());
        }
    }

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
                //Specify a unix local domain as localhost to limit to local connections only
                String qmpParams = "unix:";
                qmpParams += LimboApplication.getLocalQMPSocketPath();
                qmpParams += ",server,nowait";
                paramsList.add(qmpParams);
            }
        }

        //Enable Tracing log
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
            paramsList.add(Config.tbSize); //Don't increase it crashes
        }

        if (LimboApplication.getQemuVersion() == 20901) {
            paramsList.add("-realtime");
            paramsList.add("mlock=off");
        } else {
            paramsList.add("-overcommit");
            paramsList.add("mem-lock=off");
        }

        paramsList.add("-rtc");
        paramsList.add("base=localtime");
    }

    private void addCpuBoardOptions(ArrayList<String> paramsList) {
        //XXX: SMP is not working correctly for some guest OSes
        //so we enable multi core only under KVM
        // anyway regular emulation is not gaining any benefit unless mttcg is enabled but that
        // doesn't work for x86 guests yet
        if (getMachine().getCpuNum() > 1) {
            paramsList.add("-smp");
            paramsList.add(getMachine().getCpuNum() + "");
        }
        if (getMachineType() != null && !getMachineType().equals("Default")) {
            String machineParams = getMachineType();
            // IA-64 only: i8042=off is appended when the user disables the
            // i8042 PS/2 controller, and nvram=<path> when NVRAM is enabled
            // (the app-managed file is used when no explicit path is set).
            // Windows XP / Server 2003 IA64 text-mode setup cannot use PS/2,
            // so i8042=off makes QEMU attach a USB keyboard; without it the
            // "Press any key to boot from CD" prompt times out and the loader
            // hangs after "Continuing normal boot."  Other architectures must
            // not receive these options.
            if (LimboApplication.arch == Config.Arch.ia64 || LimboApplication.arch == Config.Arch.ia64w) {
                if (getMachine().getDisableI8042() == 1) {
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

        //FIXME: something is wrong with quoting that doesn't let sparc qemu find the cpu def
        // for now we remove the cpu drop downlist items for sparc
        String cpu = getMachine().getCpu();
        if (getMachine().getCpu() != null && getMachine().getCpu().contains(" "))
            cpu = "'" + getMachine().getCpu() + "'"; // XXX: needed for sparc cpu names

        //XXX: we disable tsc feature for x86 since some guests are kernel panicking
        // if the cpu has not specified by user we use the internal qemu32/64
        if (getMachine().getDisableTSC() == 1 && (LimboApplication.arch == Config.Arch.x86 || LimboApplication.arch == Config.Arch.x86_64)) {
            if (cpu == null || cpu.equals("Default")) {
                if (LimboApplication.arch == Config.Arch.x86)
                    cpu = "qemu32";
                else if (LimboApplication.arch == Config.Arch.x86_64)
                    cpu = "qemu64";
            }
            cpu += ",-tsc";
        }

        // ACPI/HPET disabling is an x86-only concept. QEMU 9.0 removed the
        // -no-acpi/-no-hpet switches and turned them into machine properties
        // (acpi=off / hpet=off); those properties only exist on x86 machines,
        // so other targets (ia64, arm, ...) must not receive them at all.
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

        if (cpu != null && !cpu.equals("Default")) {
            paramsList.add("-cpu");
            paramsList.add(cpu);
        }

        paramsList.add("-m");
        paramsList.add(getMachine().getMemory() + "");
    }


    private void addAccelerationOptions(ArrayList<String> paramsList) {

        // XXX: we add the acceleration options after the extra params
        // this is due to QEMU applying the first instance of this option
        // so the extra params cannot override it.
        if (getMachine().getEnableKVM() != 0) {
            paramsList.add("-enable-kvm");
        } else {
            paramsList.add("-accel");
            String tcgParams = "tcg";
            if (getMachine().getEnableMTTCG() != 0) {
                tcgParams += ",thread=multi";
            } else {
                tcgParams += ",thread=single";
            }
            paramsList.add(tcgParams);
        }
    }

    private String getMachineType() {
        String machineType = getMachine().getMachineType();
        if ((LimboApplication.arch == Config.Arch.x86 || LimboApplication.arch == Config.Arch.x86_64)
                && machineType == null) {
            machineType = "pc";
        }
        return machineType;
    }

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
                        // example forward ssh from guest port 2222 to guest port 22:
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
                    //Unknown interface
                    paramsList.add("none");
                    break;
            }
        }

        String networkCard = getNicCard();
        if (networkCard != null) {
            paramsList.add("-net");
            String nicParams = "nic";
            if (network.equals("tap"))
                nicParams += ",vlan=0";
            if (!networkCard.equals("Default"))
                nicParams += (",model=" + networkCard);
            paramsList.add(nicParams);
        }
    }

    private String getHostFwd() {
        if (getMachine().getNetwork().equals("User")) {
            if (getMachine().getHostFwd() != null && !getMachine().getHostFwd().isEmpty())
                return getMachine().getHostFwd();
        }
        return null;
    }

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

    private void addGraphicsOptions(ArrayList<String> paramsList) {
        if (getMachine().getVga() != null) {
            if (getMachine().getVga().equals("Default")) {
                //do nothing
            } else if (getMachine().getVga().equals("virtio-gpu-pci")) {
                paramsList.add("-device");
                paramsList.add(getMachine().getVga());
            } else if (getMachine().getVga().equals("nographic")) {
                paramsList.add("-nographic");
            } else {
                paramsList.add("-vga");
                paramsList.add(getMachine().getVga());
            }
        }
    }

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

    private String getBootDevice() {
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
     * Adds the "-bios" option. If the user picked a firmware from the BIOS
     * dropdown (assets/roms file name stored in the machine), that file is
     * used; otherwise the SeaBIOS shipped in the app assets is used.
     * Applied to every architecture: x86/x86_64 PC machines use it as their
     * default firmware, the IA-64 ia64-vpc (itanium2-vpc) machine requires a
     * "-bios" firmware ROM to boot, and ARM boards fall back to it when they
     * need a firmware blob.
     */
    private void addBIOSOption(ArrayList<String> paramsList) {
        String bios = getMachine() != null ? getMachine().getBios() : null;
        if (bios != null && !bios.isEmpty() && !bios.equals("None")) {
            // user-selected firmware from the BIOS dropdown
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
        // QEMU 10.x defaults to bios-256k.bin on PC machines; bios.bin is the
        // legacy 128K SeaBIOS kept as a fallback.
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

    private String getInitRd() {
        return FileUtils.encodeDocumentFilePath(getMachine().getInitRd());
    }

    private String getKernel() {
        return FileUtils.encodeDocumentFilePath(getMachine().getKernel());
    }

    public String getDriveFilePath(String driveFilePath) {
        String imgPath = driveFilePath;
        if (imgPath == null || imgPath.equals("None"))
            return null;
        imgPath = FileUtils.encodeDocumentFilePath(imgPath);
        return imgPath;
    }

    private boolean isRawImage(String imagePath) {
        if (imagePath == null)
            return false;
        String lower = imagePath.toLowerCase();
        return lower.endsWith(".img") || lower.endsWith(".raw");
    }

    /**
     * Resolves the -drive if= interface. A null/empty value falls back to "ide"
     * (QEMU's default bus) — same as before per-drive interfaces were exposed.
     */
    private String resolveDriveInterface(String iface) {
        if (iface == null || iface.trim().isEmpty())
            return "ide";
        return iface;
    }

    /**
     * Computes the -drive bus index (unit) for a hard disk on a given slot.
     *
     * On the IA-64 machine the boot CD-ROM is fixed at if=ide index 0, so IDE
     * hard disks are offset by +1 (slot 0 -> index 1).  A drive on the LSI SCSI
     * bus is a different address space: its index maps 1:1 to the SCSI target
     * ID, and the EFI firmware exposes the disk at Scsi(0,0).  To be recognized
     * by the firmware the first SCSI disk must therefore sit at target 0, i.e.
     * its slot (0) with no offset.  Non-IA-64 targets use the bare slot as well.
     */
    private int ia64DriveIndex(boolean iaDisk, String iface, int slot) {
        if (!iaDisk || "scsi".equals(iface)) {
            return slot;
        }
        return slot + 1;
    }

    /**
     * Resolves the -drive format=. A null/empty/"auto" value keeps the legacy
     * behavior: hard disks get "raw" only for raw images (otherwise auto-detect),
     * CD-ROMs always use "raw". Any concrete format the user set is used as-is.
     *
     * @param explicit      the stored per-drive format (null when unset)
     * @param path          the image file path (used by the raw detection)
     * @param isDisk        true for hard disks, false for the CD-ROM
     */
    private String resolveDriveFormat(String explicit, String path, boolean isDisk) {
        if (explicit == null || explicit.trim().isEmpty() || explicit.equals("auto")) {
            return isDisk ? (isRawImage(path) ? "raw" : null) : "raw";
        }
        return explicit;
    }

    /**
     * Emits all storage devices (HDA..HDD, CDROM, FDA/FDB, SD card and the
     * shared folder) as uniform "-drive" parameters.
     */
    public void addDrives(ArrayList<String> paramsList) {
        String cache = LimboSettingsManager.getDiskCache(LimboApplication.getInstance());
        if (cache == null || cache.equals("default"))
            cache = null;

        // The IA-64 machine exposes an LSI SCSI HBA and a CMD646 legacy IDE
        // controller.  Windows XP/Server 2003 IA64 ships with in-box IDE/ATAPI
        // and LSI/Symbios SCSI drivers but has no SATA/AHCI driver, so the
        // whole IA-64 storage stack is put on the CMD646 legacy IDE bus: the
        // boot CD-ROM is its primary master (if=ide index 0) and hard disks
        // follow at index 1 so they never collide with the CD.  Routing the
        // target disk to the LSI SCSI HBA instead gives the installer a
        // graphical blue-screen with STOP 0x7E
        // (SYSTEM_THREAD_EXCEPTION_NOT_HANDLED) in the Symbios SCSI driver.
        boolean iaDisk = LimboApplication.arch == Config.Arch.ia64
                || LimboApplication.arch == Config.Arch.ia64w;

        // Hard disks HDA..HDD. if= comes from the machine's per-drive interface
        // (null/empty -> "ide", QEMU's default). format= comes from the machine's
        // per-drive format when set, otherwise the legacy auto/raw detection.
        //
        // IMPORTANT (IA-64): on the ia64-vpc machine the boot CD-ROM owns if=ide
        // index 0, so IDE hard disks must start at index 1.  But the LSI SCSI bus
        // is a separate address space: for a drive on it, -drive index maps 1:1 to
        // the SCSI target ID (scsi index 0 -> target 0).  The EFI firmware exposes
        // a SCSI disk at its exact device path Pci(4,0)/Scsi(0,0); bumping the
        // index to 1 (our legacy IDE-only offset) silently moves the disk to
        // target 1, where the firmware neither enumerates nor boots it.  So the
        // +1 offset is applied to the IDE bus only; SCSI drives start at target 0,
        // matching the Windows command line ("if=scsi,index=0") that is known to
        // work in the EFI Shell.
        String ifaceHda = resolveDriveInterface(getMachine().getHdaInterface());
        String ifaceHdb = resolveDriveInterface(getMachine().getHdbInterface());
        String ifaceHdc = resolveDriveInterface(getMachine().getHdcInterface());
        String ifaceHdd = resolveDriveInterface(getMachine().getHddInterface());
        addDrive(paramsList, Integer.toString(ia64DriveIndex(iaDisk, ifaceHda, 0)),
                ifaceHda, "disk", null,
                getDriveFilePath(getMachine().getHdaImagePath()),
                resolveDriveFormat(getMachine().getHdaFormat(),
                        getDriveFilePath(getMachine().getHdaImagePath()), true), cache);
        addDrive(paramsList, Integer.toString(ia64DriveIndex(iaDisk, ifaceHdb, 1)),
                ifaceHdb, "disk", null,
                getDriveFilePath(getMachine().getHdbImagePath()),
                resolveDriveFormat(getMachine().getHdbFormat(),
                        getDriveFilePath(getMachine().getHdbImagePath()), true), cache);
        addDrive(paramsList, Integer.toString(ia64DriveIndex(iaDisk, ifaceHdc, 2)),
                ifaceHdc, "disk", null,
                getDriveFilePath(getMachine().getHdcImagePath()),
                resolveDriveFormat(getMachine().getHdcFormat(),
                        getDriveFilePath(getMachine().getHdcImagePath()), true), cache);
        if (!iaDisk) {
            addDrive(paramsList, Integer.toString(ia64DriveIndex(iaDisk, ifaceHdd, 3)),
                    ifaceHdd, "disk", null,
                    getDriveFilePath(getMachine().getHddImagePath()),
                    resolveDriveFormat(getMachine().getHddFormat(),
                            getDriveFilePath(getMachine().getHddImagePath()), true), cache);
        }

        // CDROM getMachine().getCDInterface()
        // IA-64: the boot CD-ROM is wired to the CMD646 legacy PCI IDE
        // controller as primary master (if=ide index 0), so the firmware boots
        // from it and Windows XP/Server 2003 IA64 reads it with its in-box
        // IDE/ATAPI driver.  The other interfaces cannot install 2003: an LSI
        // SCSI CD-ROM hangs the firmware's SCSI path, and a SATA/AHCI CD-ROM
        // has no Windows IA64 driver ("txtsetup.inf is corrupt or missing").
        // Keep the historical if=scsi behavior for every other architecture.
        boolean ia64cd = LimboApplication.arch == Config.Arch.ia64
                || LimboApplication.arch == Config.Arch.ia64w;
        String cdInterface = getMachine().getCDInterface();
        if (cdInterface == null || cdInterface.trim().isEmpty()) {
            // legacy arch default when the user has not overridden the interface
            cdInterface = ia64cd ? "ide" : "scsi";
        }
        String cdPath = getDriveFilePath(getMachine().getCdImagePath());
        addDrive(paramsList, ia64cd ? "0" : null,
                cdInterface, "cdrom", null,
                cdPath, resolveDriveFormat(getMachine().getCDFormat(), cdPath, false), null);

        // Floppy drives FDA/FDB
        if (Config.enableEmulatedFloppy) {
            addDrive(paramsList, "0", "floppy", null, null,
                    getDriveFilePath(getMachine().getFdaImagePath()), null, null);
            addDrive(paramsList, "1", "floppy", null, null,
                    getDriveFilePath(getMachine().getFdbImagePath()), null, null);
        }

        // SD card: -drive if=none,id=sd0 paired with the sd-card device
        if (Config.enableEmulatedSDCard) {
            String sdImagePath = getDriveFilePath(getMachine().getSdImagePath());
            if (sdImagePath != null) {
                paramsList.add("-device");
                paramsList.add("sd-card,drive=sd0,bus=sd-bus");
                addDrive(paramsList, null, "none", null, "sd0", sdImagePath, null, null);
            }
        }

        // Shared folder mounted as a virtual FAT drive
        if (Config.enableSharedFolder) {
            String sharedFolderPath = getDriveFilePath(getMachine().getSharedFolderPath());
            if (sharedFolderPath != null) {
                addDrive(paramsList, "3", "ide", "disk", null,
                        "fat:rw:" + sharedFolderPath, "raw", null);
            }
        }
    }

    /**
     * Appends a single "-drive" parameter to paramsList. All storage devices
     * go through this helper so the QEMU command line stays uniform.
     *
     * @param index  bus index ("0".."3") or null when if=none (SD card)
     * @param iface  interface: ide, scsi, virtio, floppy, none, ...
     * @param media  media type: disk, cdrom or null
     * @param id     drive id (used for if=none drives such as sd0)
     * @param file   image file path, or "fat:rw:<dir>" for shared folders
     * @param format force the format (raw) or null for auto-detection
     * @param cache  cache mode or null
     */
    private void addDrive(ArrayList<String> paramsList, String index, String iface, String media,
                          String id, String file, String format, String cache) {
        if (file == null || file.trim().isEmpty())
            return;
        StringBuilder param = new StringBuilder();
        if(index != null)
            appendDriveField(param, "index", index);
        if(iface != null)
            appendDriveField(param, "if", iface);
        appendDriveField(param, "media", media);
        appendDriveField(param, "id", id);
        appendDriveField(param, "file", file);
        appendDriveField(param, "format", format);
        appendDriveField(param, "cache", cache);
        paramsList.add("-drive");
        paramsList.add(param.toString());
    }

    private void appendDriveField(StringBuilder param, String field, String value) {
        if (value == null || value.isEmpty())
            return;
        if (param.length() > 0)
            param.append(",");
        param.append(field).append("=").append(value);
    }


    /**
     * change the vnc password before we connect
     * The user is also prompted to create a certificate
     *
     * @param vncPassword The VNC password to be send to QEMU
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

    protected String changedev(String dev, String value) {
        String response = QmpClient.sendCommand(QmpClient.getChangeDeviceCommand(dev, value));
        String displayDevValue = FileUtils.getFullPathFromDocumentFilePath(value);
        if (Config.debug)
            ToastUtils.toastLong(LimboApplication.getInstance(), Gravity.BOTTOM,
                    LimboApplication.getInstance().getString(R.string.ChangedDevice) + ": "
                            + dev + ": " + displayDevValue);
        return response;
    }

    protected String ejectdev(String dev) {
        String response = QmpClient.sendCommand(QmpClient.getEjectDeviceCommand(dev));
        if (Config.debug)
            ToastUtils.toastLong(LimboApplication.getInstance(), Gravity.BOTTOM,
                    LimboApplication.getInstance().getString(R.string.EjectedDevice) + ": " + dev);
        return response;
    }


    /**
     * Starts the service that will later start the qemu process
     */
    public void startService() {
        Intent i = new Intent(Config.ACTION_START, null, LimboApplication.getInstance(),
                MachineController.getInstance().getServiceClass());
        Bundle b = new Bundle();
        i.putExtras(b);
        Log.d(TAG, "Starting VM service");
        // API 26+ requires startForegroundService() for services that call
        // startForeground() (MachineService does). Using startService() there is
        // subject to background service start restrictions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LimboApplication.getInstance().startForegroundService(i);
        } else {
            LimboApplication.getInstance().startService(i);
        }
    }

    /**
     * Starts the native process. This should be called from a background thread from a
     * foreground service in order to prevent the process from being killed
     *
     * @return String from the native code vm-executor-jni.cpp
     */
    public String start() {
        String res = null;
        try {
            String[] params = prepareParams(LimboApplication.getInstance());
            printParams(params);
            // XXX: for VNC we need to resume manually after a reasonable amount of time
            if (getMachine().getPaused() == 1 && MachineController.getInstance().isVNCEnabled()) {
                continueVM(5000);
            }

            if (MachineController.getInstance().isVNCEnabled() && LimboSettingsManager.getVNCEnablePassword(LimboApplication.getInstance())) {
                changeVncPass(LimboApplication.getInstance(), 2000);
            }

            ignoreBreakpointInvalidation(LimboSettingsManager.getIgnoreBreakpointInvalidation(LimboApplication.getInstance())?1:0, 2000);
            QmpClient.setExternal(LimboSettingsManager.getEnableExternalQMP(LimboApplication.getInstance()));
            // Read at VM start so the setting takes effect for the current run.
            String libFilename = getQemuLibrary();
            res = start(Config.storagedir, LimboApplication.getBasefileDir(),
                    libFilename, FileUtils.getNativeLibDir(LimboApplication.getInstance()) + "/" + libFilename,
                    Config.SDLHintScale, params);
        } catch (Exception ex) {
            ToastUtils.toastLong(LimboApplication.getInstance(), ex.getMessage());
            return res;
        }
        return res;
    }

    private void changeVncPass(final Context context, final long delay) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                try {
                    vncchangepassword(LimboSettingsManager.getVNCPass(context));
                } catch (Exception e) {
                    ToastUtils.toastLong(LimboApplication.getInstance(),
                            context.getString(R.string.CouldNotSetVNCPass) + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void continueVM(final int delay) {
        // TODO: We shouldn't have to go through the view dispatcher
        LimboApplication.getViewListener().onAction(MachineAction.CONTINUE_VM, delay);
    }

    public void stopvm(final int restart) {
        new Thread(() -> {
            if (restart != 0) {
                QmpClient.sendCommand(QmpClient.getResetCommand());
            } else {
                //XXX: Qmp command only halts the VM but doesn't exit so we use force close
//            QmpClient.sendCommand(QmpClient.powerDown());
                stop(restart);
            }
        }).start();
    }

    @Override
    public int getSdlRefreshRate(boolean idle) {
        if (idle)
            return getSDLRefreshRateIdle();
        else
            return getSDLRefreshRateDefault();
    }

    @Override
    public void setSdlRefreshRate(int value, boolean idle) {
        if (idle)
            setSDLRefreshRateIdle(value);
        else
            setSDLRefreshRateDefault(value);
    }

    @Override
    public String getDeviceName(@NonNull MachineProperty driveProperty) {
        // On IA-64 the CD-ROM lives on the LSI SCSI bus (unit 4, see
        // addDrives()), so its QMP id is "scsi0-cd4" instead of the
        // legacy "ide1-cd0" used on the other architectures.
        if (driveProperty == MachineProperty.CDROM) {
            if (LimboApplication.arch == Config.Arch.ia64
                    || LimboApplication.arch == Config.Arch.ia64w) {
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
        }
        return null;
    }

    @Override
    public synchronized void updateDisplay(int width, int height, int orientation) {
        if (!LimboSettingsManager.getPreventMouseOutOfBounds(LimboApplication.getInstance())) {
            return;
        }
        String mouse = getMachine().getMouse();
        // If we use absolute pointer devices in the guest os (usb-tablet) we need to prevent
        // the mouse from going out of bounds. This case happens when we use trackpad and when the
        // guest display doesn't fit inside the Android Surface which is pretty much all the time.
        // we could use SurfaceHolder.setFixedSize() to bound the surfaceview but it creates
        // problems with refreshing the surfaceview plus we would still need this fix for trackpad
        if (mouse != null && mouse.equals("usb-tablet") && vm_width > 0 && vm_height > 0) {
            // Compute the letterboxed (aspect-ratio-preserving) display region the
            // same way the QEMU SDL backend does (scale = MIN(w/vm_w, h/vm_h),
            // centered), so the mouse bounds always match the on-screen guest image.
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
        //TODO: sparc doesn't not have vga so we need to
        // see if we can apply similar call to the cg3
        if(LimboApplication.arch == Config.Arch.x86
                || LimboApplication.arch == Config.Arch.x86_64
                || LimboApplication.arch == Config.Arch.arm
                || LimboApplication.arch == Config.Arch.arm64
                || LimboApplication.arch == Config.Arch.ia64
                || LimboApplication.arch == Config.Arch.ia64w
        ) {
            nativeRefreshScreen(1);
        }
    }

    @Override
    public void enableAaudio(int value) {
        nativeEnableAaudio(value, Config.aaudioLibName,
                FileUtils.getNativeLibDir(LimboApplication.getInstance())
                        + "/" + Config.aaudioLibName);
    }

    @Override
    public void ignoreBreakpointInvalidation(int value){
        ignoreBreakpointInvalidation(value, 0);
    }

    private void ignoreBreakpointInvalidation(final int value, final long delay) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                nativeIgnoreBreakpointInvalidate(value);
            }
        }).start();
    }

    //TODO: re-enable getting status from the vm
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
                e.printStackTrace();
            }
        }
        return state;
    }

    /**
     * Function sends a command via qmp to change or eject the removable device
     *
     * @param drive     The device to be changed
     * @param imagePath If its null it ejects the drive otherwise it uses the disk file at that path
     */
    public boolean changeRemovableDevice(final MachineProperty drive, final String imagePath) {
        if (!LimboSettingsManager.getEnableQmp(LimboApplication.getInstance())) {
            ToastUtils.toastShort(LimboApplication.getInstance(), LimboApplication.getInstance().getString(R.string.EnableQMPForChangingDrives));
            return false;
        }
        String dev = getDeviceName(drive);

        //XXX: first we eject any previous media
        String response = VMExecutor.this.ejectdev(dev);

        // if there is no media there is nothing else to do
        if (imagePath == null || imagePath.trim().isEmpty()) {
            return true;
        }

        //XXX: we encode some characters from the document file path so it's processed
        // correctly by qemu
        String imagePathConverted = FileUtils.encodeDocumentFilePath(imagePath);

        if (!FileUtils.fileValid(imagePathConverted)) {
            String msg = LimboApplication.getInstance().getString(R.string.CouldNotOpenDocFile) + " "
                    + FileUtils.getFullPathFromDocumentFilePath(imagePathConverted)
                    + "\n" + LimboApplication.getInstance().getString(R.string.PleaseReassingYourDiskFiles);
            ToastUtils.toastLong(LimboApplication.getInstance(), msg);
            return false;
        }
        response = VMExecutor.this.changedev(dev, imagePathConverted);
        return response != null;
    }

    /**
     * Fuction is a pass thru from the c get_fd() function called from native code
     * This is bridged to the java code because it's the only way to open a file descriptor
     * from the native code
     *
     * @param path File path
     * @return Return value of FileUtils.get_fd()
     */
    public int get_fd(String path) {
        return FileUtils.get_fd(path);
    }

    /**
     * Fuction is a pass thru from the c close_fd() function called from native code
     * This is similar to the above get_fd but perhaps not needed.
     *
     * @param fd File Descriptor to be closed
     * @return Return value of FileUtils.close_fd()
     */
    public int close_fd(int fd) {
        return FileUtils.close_fd(fd);
    }

    @Override
    public String saveVM() {

        // Delete any previous state file
        File file = new File(getSaveStateName());
        if (file.exists()) {
            if (!file.delete()) {
                return LimboApplication.getInstance().getString(R.string.CannotDeletePreviousStateFile);
            }
        }

        if (Config.showToast)
            ToastUtils.toastShort(LimboApplication.getInstance(), LimboApplication.getInstance().getString(R.string.PleaseWaitSavingVMState));

        // QEMU 10.x no longer resolves numeric "fd:" migration URIs from the
        // QMP monitor (monitor_get_fd only finds named fds registered via the
        // getfd command), so use the "file:" scheme which opens the state file
        // path directly.
        String uri = "file:" + getSaveStateName();
        String command = QmpClient.getStopVMCommand();
        QmpClient.sendCommand(command);
        command = QmpClient.getMigrateCommand(false, false, uri);
        String msg = QmpClient.sendCommand(command);
        if (msg != null) {
            return processMigrationResponse(msg);
        }
        return null;
    }

    @Override
    public void continueVM() {
        String command = QmpClient.getContinueVMCommand();
        QmpClient.sendCommand(command);
    }

    @Override
    public MachineController.MachineStatus getSaveVMStatus() {
        String pauseState = "";
        String command = QmpClient.getQueryMigrationCommand();
        String res = QmpClient.sendCommand(command);

        if (res != null && !res.isEmpty()) {
            try {
                JSONObject resObj = new JSONObject(res);
                String resInfo = resObj.getString("return");
                JSONObject resInfoObj = new JSONObject(resInfo);
                // QEMU omits the "status" member when no migration is in
                // progress (state MIGRATION_STATUS_NONE); don't throw on that,
                // just leave pauseState empty so the poller can retry.
                if (resInfoObj.has("status"))
                    pauseState = resInfoObj.getString("status");
            } catch (JSONException e) {
                if (Config.debug)
                    Log.e(TAG, "Error while checking saving vm: " + e.getMessage());
            }
            if (pauseState.toUpperCase().equals("FAILED")) {
                Log.e(TAG, "Error: " + res);
            }
        }
        if (pauseState.toUpperCase().equals("ACTIVE")
                || pauseState.toUpperCase().equals("SETUP")) {
            return MachineController.MachineStatus.Saving;
        } else if (pauseState.toUpperCase().equals("COMPLETED")) {
            return MachineController.MachineStatus.SaveCompleted;
        } else if (pauseState.toUpperCase().equals("FAILED")
                || pauseState.toUpperCase().equals("CANCELLED")) {
            return MachineController.MachineStatus.SaveFailed;
        }
        //TODO: proper error handling with user messages
        return MachineController.MachineStatus.Unknown;
    }

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

    public void sendMouseEvent(int button, int action, int relative, float x, float y) {
        //XXX: Make sure that mouse motion is not triggering crashes in SDL while resizing
        if (LimboSDLActivity.isResizing) {
            return;
        }

        nativeMouseEvent(button, action, relative, (int) x, (int) y);
    }

    public boolean getQMPAllowExternal() {
        return LimboSettingsManager.getEnableExternalQMP(LimboApplication.getInstance());
    }
}

