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

import android.os.Process;

import java.io.File;

/** Root helpers used when a hardware accelerator (gunyah/gzvm) requires the
 * VM to run in a root process. */
public class RootUtils {
    private static final String TAG = "RootUtils";

    private static final String[] SU_PATHS = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/bin/.magisk/su",
            "/system/bin/.magisk/mirror/system/bin/su",
    };

    private RootUtils() {
    }

    /** True when the current process is already running as root. */
    public static boolean isRoot() {
        try {
            return Process.myUid() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Best-effort check for a usable su binary. A missing check does not
     * necessarily mean su is absent (custom paths exist), so the real
     * authority is whether the su launch itself succeeds. */
    public static boolean hasSu() {
        for (String path : SU_PATHS) {
            if (new File(path).exists()) {
                return true;
            }
        }
        return false;
    }
}
