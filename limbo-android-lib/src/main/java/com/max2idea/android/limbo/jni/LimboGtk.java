package com.max2idea.android.limbo.jni;

import android.content.Context;
import android.util.Log;

/**
 * Initializes the GDK android backend so QEMU can use "-display gtk".
 *
 * <p>The native counterpart (limbo_gtk.c in jni/compat) dlopens libgtk-4.so
 * and calls {@code gdk_android_initialize()} with the JNI environment, the
 * application class loader and the host activity (a
 * {@code org.gtk.android.ToplevelActivity} subclass).  GTK itself is
 * initialized by QEMU's gtk display backend on the QEMU UI thread.
 */
public class LimboGtk {
    private static final String TAG = "LimboGtk";
    private static boolean initialized = false;

    static {
        try {
            System.loadLibrary("compat-limbo");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libcompat-limbo.so: " + e.getMessage());
        }
    }

    private static native void nativeInit(Object classLoader, Object activity);

    /**
     * Must be called from the GTK host activity (on the main thread) before
     * the VM is started.
     */
    public static synchronized void init(Context context) {
        if (initialized) {
            return;
        }
        nativeInit(context.getClassLoader(), context);
        initialized = true;
    }
}
