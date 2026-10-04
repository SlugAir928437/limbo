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
package com.limbo.emu.main;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import com.limbo.emu.MainActivity;
import com.limbo.emu.R;
import com.limbo.emu.log.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Central place that owns the <b>emulated architecture</b> selection.
 *
 * <p>An APK can bundle more than one QEMU engine (for example
 * {@code libqemu-system-x86_64.so} and {@code libqemu-system-aarch64.so}).
 * Only one engine can be resident in a process, so the user picks the target
 * architecture here, the choice is persisted, and the app is restarted so the
 * matching engine is loaded from scratch.
 *
 * <p>Every architecture also gets its own virtual-machine namespace:
 * <ul>
 *     <li>the machine rows in the database are tagged with the exact
 *     architecture and all queries are scoped to it ({@link
 *     com.limbo.emu.machine.MachineOpenHelper});</li>
 *     <li>the per-VM state directory differs per architecture ({@link
 *     #machineFolderFor}).</li>
 * </ul>
 * Saved virtual machines therefore never leak between architectures.
 */
public final class ArchManager {
    private static final String TAG = "ArchManager";

    /** SharedPreferences key holding the persisted {@link Config.Arch} name. */
    public static final String PREFS_KEY_ARCH = "emulatedArch";

    /** Stable request code for the relaunch {@link PendingIntent}. */
    private static final int RESTART_REQUEST_CODE = 0x11A64;

    /**
     * Engine probe order. Kept identical to the historical auto-detection order
     * in {@code MainActivity#checkQEMULib} so existing installs keep running the
     * same engine they did before this setting existed.
     *
     * <p>The legacy {@link Config.Arch#ia64w} value is intentionally absent: it
     * only exists so already-saved IA-64 machine rows keep their database tag and
     * no {@code libqemu-system-ia64w.so} engine has ever been shipped.
     */
    private static final Config.Arch[] PROBE_ORDER = {
            Config.Arch.x86_64,
            Config.Arch.x86,
            Config.Arch.arm,
            Config.Arch.arm64,
            Config.Arch.ia64
    };

    /**
     * The guest architectures offered by the "Switch Architecture" picker.
     *
     * <p>These are exactly the engines the native build can produce (see
     * {@code jni/Makefile}, target {@code INSTALL_QEMU_LIBS}): 32- and 64-bit
     * x86, 32- and 64-bit ARM, and the IA-64 fork -- five entries in total. The
     * list is intentionally split per variant instead of per family so the user
     * can pick the exact guest bitness.
     */
    private static final Config.Arch[] SELECTABLE_ARCHS = {
            Config.Arch.x86,
            Config.Arch.x86_64,
            Config.Arch.arm,
            Config.Arch.arm64,
            Config.Arch.ia64
    };

    private ArchManager() {
    }

    /**
     * @return the JNI library file name for {@code arch}, e.g.
     * {@code libqemu-system-x86_64.so}
     */
    public static String libName(Config.Arch arch) {
        switch (arch) {
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
                throw new IllegalArgumentException("Unknown architecture: " + arch);
        }
    }

    /**
     * @return the name accepted by {@link System#loadLibrary(String)} for
     * {@code arch}, i.e. the file name without the {@code lib} prefix and the
     * {@code .so} suffix.
     */
    public static String libraryName(Config.Arch arch) {
        String name = libName(arch);
        return name.substring("lib".length(), name.length() - ".so".length());
    }

    /**
     * The machine state directory (relative to the base files dir) used by
     * {@code arch}. Every architecture maps to a distinct folder so two VMs that
     * happen to share a name on different architectures cannot collide.
     */
    public static String machineFolderFor(Config.Arch arch) {
        switch (arch) {
            case arm64:
                // legacy folder, kept so already-saved ARM64 VMs keep their state
                return "machines/other/arm_machines/";
            case ia64:
                // legacy folder, kept for already-saved IA-64 VMs
                return "machines/other/ia64_machines/";
            case x86:
                return "machines/other/x86_machines/";
            case arm:
                return "machines/other/arm32_machines/";
            case ia64w:
                return "machines/other/ia64w_machines/";
            case x86_64:
            default:
                // legacy default folder, kept for already-saved x86_64 VMs
                return "machines/";
        }
    }

    /**
     * Names of the native libraries bundled in this build, resolved once per
     * process. Scanning the (potentially large) APK on every lookup would be too
     * expensive during startup.
     */
    private static Set<String> bundledLibNames;

    private static synchronized Set<String> getBundledLibNames(Context context) {
        if (bundledLibNames != null)
            return bundledLibNames;

        Set<String> names = new HashSet<>();

        // 1) libraries extracted next to the app
        try {
            String dir = context.getApplicationInfo().nativeLibraryDir;
            File libDir = dir != null ? new File(dir) : null;
            if (libDir != null && libDir.isDirectory()) {
                File[] files = libDir.listFiles();
                if (files != null)
                    for (File f : files)
                        names.add(f.getName());
            }
        } catch (Exception e) {
            Log.d(TAG, "nativeLibraryDir probe failed: " + e.getMessage());
        }

        // 2) libraries packaged uncompressed inside the (split) APKs, which is
        //    the default packaging on modern Android (extractNativeLibs=false)
        try {
            List<String> apks = new ArrayList<>();
            String base = context.getApplicationInfo().sourceDir;
            if (base != null)
                apks.add(base);
            String[] splits = context.getApplicationInfo().splitSourceDirs;
            if (splits != null)
                for (String split : splits)
                    if (split != null)
                        apks.add(split);

            for (String apk : apks) {
                ZipFile zip = null;
                try {
                    zip = new ZipFile(apk);
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.startsWith("lib/") && name.endsWith(".so"))
                            names.add(name.substring(name.lastIndexOf('/') + 1));
                    }
                } finally {
                    if (zip != null) {
                        try {
                            zip.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "APK native lib probe failed: " + e.getMessage());
        }

        bundledLibNames = names;
        return bundledLibNames;
    }

    /**
     * Checks whether the QEMU engine for {@code arch} is bundled in this build.
     *
     * <p>Works both when the native libraries are extracted next to the app
     * ({@code nativeLibraryDir}) and when they are packaged uncompressed inside
     * the APK ({@code extractNativeLibs=false}), which is the default on
     * modern Android.
     */
    public static boolean isArchAvailable(Context context, Config.Arch arch) {
        if (context == null || arch == null)
            return false;
        return getBundledLibNames(context).contains(libName(arch));
    }

    /**
     * @return every architecture whose QEMU engine is bundled in this build,
     * ordered by the legacy probe priority.
     */
    public static List<Config.Arch> getAvailableArchs(Context context) {
        List<Config.Arch> result = new ArrayList<>();
        for (Config.Arch arch : PROBE_ORDER) {
            if (isArchAvailable(context, arch))
                result.add(arch);
        }
        return result;
    }

    /**
     * @return the five guest architectures shown by the "Switch Architecture"
     * picker, in display order. Entries whose engine is missing from this build
     * are included as well, so the picker always presents the same split into
     * five architectures; {@link #isArchAvailable} tells which one can actually
     * be selected.
     */
    public static List<Config.Arch> getSelectableArchs() {
        return new ArrayList<>(Arrays.asList(SELECTABLE_ARCHS));
    }

    /** @return the persisted architecture, or {@code null} if the user never chose one. */
    public static Config.Arch getSelectedArch(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String value = prefs.getString(PREFS_KEY_ARCH, null);
        if (value == null || value.isEmpty())
            return null;
        try {
            return Config.Arch.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Persists the architecture chosen by the user.
     *
     * <p>Uses {@link SharedPreferences.Editor#commit()} rather than {@code
     * apply()}: the caller tears this process down immediately afterwards (a JNI
     * engine cannot be swapped in place), and a SIGKILL would abort the
     * asynchronous disk write that {@code apply()} schedules -- the choice would
     * silently be lost and the app would come back up on the old architecture.
     */
    public static void setSelectedArch(Context context, Config.Arch arch) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        if (arch == null)
            edit.remove(PREFS_KEY_ARCH);
        else
            edit.putString(PREFS_KEY_ARCH, arch.name());
        edit.commit();
    }

    /**
     * Resolves the architecture the app should run with at startup: the
     * persisted selection when its engine is still available, otherwise the
     * first bundled engine in {@link #PROBE_ORDER}.
     *
     * @return the architecture to use, or {@code null} when no engine is bundled
     * (in which case the app cannot run and reports the historical error).
     */
    public static Config.Arch resolveStartupArch(Context context) {
        Config.Arch selected = getSelectedArch(context);
        if (selected != null && isArchAvailable(context, selected))
            return selected;

        for (Config.Arch arch : PROBE_ORDER) {
            if (isArchAvailable(context, arch))
                return arch;
        }
        return null;
    }

    /** Localized, human readable name of {@code arch} (e.g. "ARM64 (aarch64)"). */
    public static String displayName(Context context, Config.Arch arch) {
        if (arch == null)
            return "";
        int resId;
        switch (arch) {
            case x86:
                resId = R.string.arch_x86;
                break;
            case x86_64:
                resId = R.string.arch_x86_64;
                break;
            case arm:
                resId = R.string.arch_arm;
                break;
            case arm64:
                resId = R.string.arch_arm64;
                break;
            case ia64:
                resId = R.string.arch_ia64;
                break;
            case ia64w:
                resId = R.string.arch_ia64w;
                break;
            default:
                return arch.name();
        }
        return context.getString(resId);
    }

    /**
     * Applies the per-architecture runtime configuration (machine directory,
     * accelerator defaults, log file). Called once per process, right after the
     * architecture is resolved.
     */
    public static void applyArchConfig(Config.Arch arch) {
        if (arch == null)
            return;
        Config.machineFolder = machineFolderFor(arch);
        switch (arch) {
            case arm64:
                Config.enableKVM = true;
                Config.enableEmulatedFloppy = false;
                Config.enableEmulatedSDCard = true;
                Logger.setupLogFile("/limbo/limbo-arm-log.txt");
                break;

            case arm:
                Config.enableKVM = false;
                Config.enableEmulatedFloppy = false;
                Config.enableEmulatedSDCard = true;
                Logger.setupLogFile("/limbo/limbo-arm-log.txt");
                break;

            case ia64:
            case ia64w:
                Config.enableKVM = false;
                Config.enableEmulatedFloppy = false;
                Config.enableEmulatedSDCard = true;
                Logger.setupLogFile("/limbo/limbo-ia64-log.txt");
                break;

            case x86:
                Config.enableKVM = false;
                Config.enableEmulatedFloppy = true;
                Config.enableEmulatedSDCard = false;
                Logger.setupLogFile("/limbo/limbo-x86-log.txt");
                break;

            case x86_64:
            default:
                Config.enableKVM = true;
                Logger.setupLogFile("/limbo/limbo-x86-log.txt");
                break;
        }
    }

    /**
     * Restarts the app so the newly selected QEMU engine is loaded in a fresh
     * process. A JNI library cannot be unloaded, so switching architectures
     * always requires a restart.
     *
     * <p>The relaunch is scheduled through {@link AlarmManager} <em>before</em>
     * the process is torn down, so the system starts the activity from a brand
     * new process: the new engine is then the first (and only) one loaded.
     * Starting the activity directly instead would recreate it inside this very
     * process, where the previous engine is still resident. The direct launch is
     * kept only as a fallback for OEM builds that refuse the alarm.
     */
    public static void restartApp(Activity activity) {
        if (activity == null)
            return;

        Intent intent = new Intent(activity, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        boolean scheduled = false;
        try {
            PendingIntent pendingIntent = PendingIntent.getActivity(activity, RESTART_REQUEST_CODE,
                    intent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager alarmManager =
                    (AlarmManager) activity.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                // Relaunch shortly after we tear this process down.
                alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 200, pendingIntent);
                scheduled = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not schedule restart: " + e.getMessage());
        }

        if (!scheduled) {
            try {
                activity.startActivity(intent);
            } catch (Exception e) {
                Log.w(TAG, "Could not launch restart activity: " + e.getMessage());
            }
        }

        activity.finish();
        // SIGKILL: tear the process down without running any destructors (see
        // MachineService), so the resident native engine is dropped cleanly.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
