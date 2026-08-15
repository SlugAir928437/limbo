package com.max2idea.android.limbo.main;

import android.os.Bundle;
import android.util.Log;

import com.max2idea.android.limbo.jni.LimboGtk;
import com.max2idea.android.limbo.machine.MachineAction;
import com.max2idea.android.limbo.machine.MachineController;

import org.gtk.android.ToplevelActivity;

/**
 * GTK4 display host activity.
 *
 * <p>Extends {@link org.gtk.android.ToplevelActivity} so the GDK android
 * backend can read this activity's {@code nativeIdentifier} field when QEMU
 * creates the display window, and so the GTK surface is hosted inside this
 * activity's view tree.
 *
 * <p>The stock auto-activation sequence is disabled: QEMU (not a
 * {@code GApplication}) drives GTK, and {@link LimboGtk#init} initializes the
 * GDK android backend before the VM starts with "-display gtk".
 */
public class LimboGtkActivity extends ToplevelActivity {
    private static final String TAG = "LimboGtkActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Disable ToplevelActivity's auto-activation BEFORE super.onCreate():
        // the default GApplication is not registered yet and activate() would
        // crash; GDK is initialized manually via LimboGtk.init() below.
        gtkAutoActivate = false;
        super.onCreate(savedInstanceState);

        LimboGtk.init(this);

        // Route the VM start through MachineService (same as SDL/VNC): this
        // records the exit code (EXIT_UNKNOWN) before the VM boots so that a
        // native crash (SIGSEGV etc.) is detected on the next app launch and
        // the log dialog is shown (see LimboActivity.checkLog()).
        new Thread(() -> {
            try {
                Log.i(TAG, "Starting VM with GTK display");
                LimboApplication.getViewListener().onAction(MachineAction.START_VM, null);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to start VM: " + ex.getMessage());
            }
        }).start();
    }
}
