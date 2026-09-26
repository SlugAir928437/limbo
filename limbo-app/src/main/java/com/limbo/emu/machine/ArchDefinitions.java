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

import android.content.Context;
import android.os.Build;

import com.limbo.emu.R;
import com.limbo.emu.install.Installer;
import com.limbo.emu.main.Config;
import com.limbo.emu.main.LimboApplication;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * A simple utility class to retrieved often long architecture attribute lists
 */
public class ArchDefinitions {
    private static String TAG = "ArchDefinitions";

    public static ArrayList<String> getSoundcards(Context context) {
        return new ArrayList<>(Arrays.asList(Installer.getAttrs(context, R.raw.common_soundcards)));
    }

    public static ArrayList<String> getNetworkDevices(Context context) {
        ArrayList<String> commonNetworkCards = new ArrayList<>(Arrays.asList(Installer.getAttrs(context, R.raw.common_nic_cards)));

        ArrayList<String> networkCards = new ArrayList<>();
        switch (LimboApplication.arch) {
            case x86:
            case x86_64:
                networkCards.add("Default");
                networkCards.addAll(commonNetworkCards);
                break;
            case arm:
            case arm64:
                networkCards.add("Default");
                networkCards.addAll(commonNetworkCards);
                networkCards.addAll(Arrays.asList(Installer.getAttrs(context, R.raw.arm_nic_cards)));
                break;
            case ia64:
            case ia64w:
                networkCards.add("Default");
                //networkCards.addAll(commonNetworkCards);
                networkCards.addAll(Arrays.asList(Installer.getAttrs(context, R.raw.ia64_nic_cards)));
                break;
        }
        return networkCards;
    }

    public static ArrayList<String> getVGAValues(Context context) {
        ArrayList<String> vgaValues = new ArrayList<>();
        if (LimboApplication.arch == Config.Arch.x86 || LimboApplication.arch == Config.Arch.x86_64
                || LimboApplication.arch == Config.Arch.arm || LimboApplication.arch == Config.Arch.arm64
                || LimboApplication.arch == Config.Arch.ia64 || LimboApplication.arch == Config.Arch.ia64w) {
            vgaValues.add("std");
            // ATI VGA is available when libqemu-system-*.*.so was built with CONFIG_ATI_VGA;
            // this is an experimental PCI vga option and may not be supported by every board
            vgaValues.add("ati");
            // QXL (qxl-vga) is a PCI VGA device that requires the SPICE stack
            // (CONFIG_QXL); only offered when libqemu-system-*.*.so was built with spice
            vgaValues.add("qxl");
        }

        if (LimboApplication.arch == Config.Arch.ia64 || LimboApplication.arch == Config.Arch.ia64w) {
            // NVIDIA Quadro2 Pro PCI VGA, provided by the merged experimental HP
            // i2000/zx6000 machines via '-vga quadro2'; experimental like ati
            vgaValues.add("quadro2");
        }

        if (LimboApplication.arch == Config.Arch.x86 || LimboApplication.arch == Config.Arch.x86_64) {
            vgaValues.add("cirrus");
            vgaValues.add("vmware");
        }

        if (LimboApplication.arch == Config.Arch.x86 || LimboApplication.arch == Config.Arch.x86_64
                || LimboApplication.arch == Config.Arch.arm || LimboApplication.arch == Config.Arch.arm64) {
            // VirtIO GPU: paravirtualized display, handed to QEMU as -device
            // (not -vga). virtio-gpu-gl-pci additionally needs a
            // libqemu-system-*.so built with CONFIG_VIRGL (virglrenderer +
            // OpenGL); when that is missing QEMU rejects the device at start.
            vgaValues.add("virtio-gpu-pci");
            vgaValues.add("virtio-gpu-gl-pci");
        }

        //XXX: some archs don't support vga on QEMU like SPARC64
        vgaValues.add("nographic");

        //TODO: Add XEN???
        // "xenfb"
        return vgaValues;
    }

    /**
     * Returns the system BIOS firmware files shipped under assets/roms that are
     * applicable to the current build architecture. Only genuine system
     * firmware is listed: VGA BIOS (vgabios-*.bin), PXE/EFI NIC option ROMs,
     * direct-kernel boot shims (linuxboot/multiboot/pvh/qboot) and firmware of
     * other architectures (OpenSBI, SLOF, PNOR, vof, openbios, ...) are
     * excluded, since they cannot be used as a machine's -bios.
     */
    public static ArrayList<String> getBiosFirmwareValues(Context context) {
        ArrayList<String> values = new ArrayList<>();
        switch (LimboApplication.arch) {
            case x86:
            case x86_64:
                // SeaBIOS 256K (QEMU 10.x default), legacy 128K SeaBIOS, and the
                // microvm machine firmware
                values.add("bios-256k.bin");
                values.add("bios.bin");
                values.add("bios-microvm.bin");
                break;
            case arm:
                values.add("ast27x0_bootrom.bin");
                break;
            case arm64:
                // Aspeed 27x0 boot ROM (ast2500/ast2600-evb and the Aspeed BMC
                // machines, which are the ARM boards selectable in the app)
                values.add("ast27x0_bootrom.bin");
                values.add("edk2-aarch64-gunyah.fd");
                values.add("edk2-aarch64-gzvm.fd");
                break;
            case ia64:
            case ia64w:
                values.add("ia64-firmware.bin");
                break;
        }
        return values;
    }

    public static ArrayList<String> getKeyboardValues(Context context) {
        ArrayList<String> arrList = new ArrayList<>();
        arrList.add("en-us");
        return arrList;
    }

    public static ArrayList<String> getMouseValues(Context context) {
        ArrayList<String> arrList = new ArrayList<>();
        // 在 i8042=off (disableI8042==1) 的情况下不提供 ps2 鼠标选项
        boolean i8042Off = false;
        Machine machine = MachineController.getInstance().getMachine();
        if (machine != null) {
            i8042Off = machine.getDisableI8042() == 1;
        }
        if (!i8042Off && LimboApplication.arch != Config.Arch.ia64
                && LimboApplication.arch != Config.Arch.ia64w) {
            arrList.add("ps2");
        }
        arrList.add("usb-mouse");
        arrList.add("usb-tablet" + " " + context.getString(R.string.fixesMouseParen));
        // VirtIO input: absolute coordinate tablet. The launcher pairs it with a
        // virtio-keyboard-pci so the guest also gets a keyboard (see
        // VMExecutor#addUIOptions). Both devices need a libqemu-system-*.so
        // built with CONFIG_VIRTIO_INPUT / CONFIG_VIRTIO_PCI.
        arrList.add("virtio-tablet-pci");
        return arrList;
    }

    public static ArrayList<String> getUIValues() {
        ArrayList<String> arrList = new ArrayList<>();
        arrList.add("VNC");
        if (Config.enable_SDL)
            arrList.add("SDL");
        // The GTK4 backend (gtk4android) is cross-compiled against API 31 and
        // depends on AMotionEvent_fromJava/AKeyEvent_fromJava, which are only
        // exported by libandroid.so on API 31+. On older devices loading the
        // VM would crash with UnsatisfiedLinkError, so hide the option there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrList.add("GTK");
        // AGL (Android Graphics Layer): the guest is rendered into a Surface
        // owned by the app (QEMU "-display agl"), which is the only display the
        // gunyah/gzvm accelerated VMs can use since those run in-process as
        // root.  It requires a libqemu-system-*.so built with the AGL backend.
        if (Config.enableAgl && (LimboApplication.arch == Config.Arch.arm
                || LimboApplication.arch == Config.Arch.arm64))
            arrList.add("AGL");
        return arrList;
    }

    public static ArrayList<String> getMachineValues(Context context) {
        ArrayList<String> machinesList = new ArrayList<>();
        machinesList.add("None");
        machinesList.add("New");
        return machinesList;
    }

    public static ArrayList<String> getCpuValues(Context context) {
        ArrayList<String> arrList = new ArrayList<>();
        switch (LimboApplication.arch) {
            case x86:
            case x86_64:
                arrList.add("Default");
                arrList.addAll(Arrays.asList(Installer.getAttrs(context, R.raw.x86_cpu)));
                break;
            case arm:
            case arm64:
                arrList.add("Default");
                arrList.addAll(Arrays.asList(Installer.getAttrs(context, R.raw.arm_cpu)));
                break;
            case ia64:
            case ia64w:
                arrList.add("Default");
                arrList.addAll(Arrays.asList(Installer.getAttrs(context, R.raw.ia64_cpu)));
                break;
        }

        if (LimboApplication.arch == Config.Arch.x86 || LimboApplication.arch == Config.Arch.x86_64
                || LimboApplication.arch == Config.Arch.arm || LimboApplication.arch == Config.Arch.arm64
                || LimboApplication.arch == Config.Arch.ia64 || LimboApplication.arch == Config.Arch.ia64w)
            arrList.add("host");
        return arrList;
    }

    public static ArrayList<String> getMachineTypeValues(Context context) {
        ArrayList<String> arrList = new ArrayList<>();
        switch (LimboApplication.arch) {
            case x86:
            case x86_64:
                arrList.add("Default");
                arrList.addAll(Arrays.asList(Installer.getAttrs(context, R.raw.x86_machine_types)));
                break;
            case arm:
            case arm64:
                arrList.addAll(Arrays.asList(Installer.getAttrs(context, R.raw.arm_machine_types)));
                break;
            case ia64:
            case ia64w:
                arrList.addAll(Arrays.asList(Installer.getAttrs(context, R.raw.ia64_machine_types)));
                break;
        }
        return arrList;
    }
}
