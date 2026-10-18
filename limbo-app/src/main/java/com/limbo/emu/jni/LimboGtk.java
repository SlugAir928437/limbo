package com.limbo.emu.jni;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

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
     *
     * <p>This deliberately runs the native initialization on every GTK
     * session instead of only once per process: the GDK android backend keeps
     * the last activity it was initialized with.  When the VM is started a
     * second time in the same process, a stale (already finished) activity
     * would still be cached and GDK would spawn a stock
     * {@code org.gtk.android.ToplevelActivity} for the new window instead of
     * claiming this one.  Re-initializing rebinds the backend to the current
     * activity so the window and its input events stay in this activity.
     */
    public static synchronized void init(Context context) {
        installBlackTheme(context);
        nativeInit(context.getClassLoader(), context);
    }

    /**
     * GTK reads {@code $XDG_CONFIG_HOME/gtk-4.0/gtk.css} during gtk_init().
     * QEMU's GTK window uses the theme's window background, which is white
     * in the default Adwaita theme; point GTK at a config dir whose user CSS
     * paints the window background black.
     */
    private static void installBlackTheme(Context context) {
        try {
            File configDir = new File(context.getFilesDir(), "gtk-config");
            File cssDir = new File(configDir, "gtk-4.0");
            if (!cssDir.isDirectory() && !cssDir.mkdirs()) {
                Log.w(TAG, "Cannot create GTK config dir " + cssDir);
                return;
            }

            File css = new File(cssDir, "gtk.css");
            String cssText = "window, window.background, .background,\n"
                    + "notebook, notebook > stack, notebook > stack > box,\n"
                    + "drawingarea, glarea {\n"
                    + "  background-color: #000000;\n"
                    + "  color: #ffffff;\n"
                    + "}\n";
            byte[] bytes = cssText.getBytes(StandardCharsets.UTF_8);
            boolean write = true;
            if (css.isFile() && css.length() == bytes.length) {
                write = false;
            }
            if (write) {
                try (FileOutputStream out = new FileOutputStream(css)) {
                    out.write(bytes);
                }
            }

            Os.setenv("XDG_CONFIG_HOME", configDir.getAbsolutePath(), true);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to install GTK black theme: " + t.getMessage());
        }
    }
}
