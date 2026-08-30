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
package com.max2idea.android.limbo.machine;

import android.content.Context;
import android.os.Build;

import com.limbo.emu.lib.R;
import com.max2idea.android.limbo.install.Installer;
import com.max2idea.android.limbo.main.Config;
import com.max2idea.android.limbo.main.LimboApplication;

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

        if (LimboApplication.arch == Config.Arch.arm || LimboApplication.arch == Config.Arch.arm64) {
            vgaValues.add("virtio-gpu-pci");
        }

        //XXX: some archs don't support vga on QEMU like SPARC64
        vgaValues.add("nographic");

        //TODO: Add XEN???
        // "xenfb"
        return vgaValues;
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
