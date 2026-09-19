package com.android.limbo.main;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import com.android.limbo.R;
import com.android.limbo.files.FileUtils;
import com.android.limbo.jni.LimboGtk;
import com.android.limbo.log.Logger;
import com.android.limbo.machine.Machine;
import com.android.limbo.machine.MachineAction;
import com.android.limbo.machine.MachineController;
import com.android.limbo.toast.ToastUtils;

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
 *
 * <p>The activity hosts the VM display without a persistent top toolbar: the
 * common VM runtime actions (video, keyboard, removable drives, volume, save
 * state, reset, shutdown, disconnect, log, help), defined in
 * {@code gtkactivitymenu.xml}, are shown as a popup menu when the back button
 * is pressed.
 */
public class LimboGtkActivity extends ToplevelActivity
        implements MachineController.OnMachineStatusChangeListener {
    private static final String TAG = "LimboGtkActivity";

    private ViewListener viewListener;
    private AudioManager am;
    protected int maxVolume;
    private boolean keyboardShown;
    public DrivesDialogBox drives = null;

    @Override
    protected View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        root.addView(getGtkView(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // The ToplevelView positions the GTK surface below the system bars
        // itself, so here we only consume the top system-bar inset.
        root.setOnApplyWindowInsetsListener((v, windowInsets) -> {
            WindowInsetsCompat compat = WindowInsetsCompat.toWindowInsetsCompat(windowInsets, v);
            Insets bars = compat.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            return compat.inset(Insets.of(0, bars.top, 0, 0)).toWindowInsets();
        });
        return root;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Disable ToplevelActivity's auto-activation BEFORE super.onCreate():
        // the default GApplication is not registered yet and activate() would
        // crash; GDK is initialized manually via LimboGtk.init() below.
        gtkAutoActivate = false;
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LimboGtk.init(this);
        setupListeners();
        setupAudio();

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

    private void showVMMenu() {
        View anchor = getGtkView();
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        getMenuInflater().inflate(R.menu.gtkactivitymenu, menu);
        Machine machine = MachineController.getInstance().getMachine();
        if (machine == null || machine.getSoundCard() == null) {
            menu.removeItem(R.id.itemVolume);
        }
        popup.setOnMenuItemClickListener(this::onMenuItemSelected);
        popup.show();
    }

    private boolean onMenuItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.itemDrives) {
            Machine machine = MachineController.getInstance().getMachine();
            if (machine != null && machine.hasRemovableDevices()) {
                drives = new DrivesDialogBox(LimboGtkActivity.this, R.style.Transparent, machine);
                drives.show();
            } else {
                ToastUtils.toastShort(this, getString(R.string.NoRemovableDevicesAttached));
            }
        } else if (id == R.id.itemReset) {
            LimboActivityCommon.promptResetVM(this, viewListener);
        } else if (id == R.id.itemShutdown) {
            LimboActivityCommon.promptStopVM(this, viewListener);
        } else if (id == R.id.itemDisconnet) {
            finish();
        } else if (id == R.id.itemKeyboard) {
            toggleKeyboard();
        } else if (id == R.id.itemVolume) {
            promptVolume();
        } else if (id == R.id.itemSaveState) {
            LimboActivityCommon.promptPause(this, viewListener);
        } else if (id == R.id.itemViewLog) {
            Logger.viewLimboLog(this);
        }
        return true;
    }

    private void toggleKeyboard() {
        keyboardShown = !keyboardShown;
        setImeKeyboardState(keyboardShown);
    }

    @Override
    public void onBackPressed() {
        // There is no persistent top toolbar; the back button brings up the
        // VM action menu (the original toolbar's items). Pressing back while
        // the popup is open dismisses it (handled by the popup window).
        showVMMenu();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    private void setupListeners() {
        viewListener = LimboApplication.getViewListener();
        MachineController.getInstance().addOnStatusChangeListener(this);
    }

    private void setupAudio() {
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null)
            maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    private void promptVolume() {
        final AlertDialog alertDialog;
        alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle(getString(R.string.Volume));

        LinearLayout.LayoutParams volParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        LinearLayout t = createVolumePanel();
        t.setLayoutParams(volParams);

        ScrollView s = new ScrollView(this);
        s.addView(t);
        alertDialog.setView(s);
        alertDialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(android.R.string.ok),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        alertDialog.cancel();
                    }
                });
        alertDialog.show();
    }

    private LinearLayout createVolumePanel() {
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(20, 20, 20, 20);
        LinearLayout.LayoutParams volparams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        SeekBar vol = new SeekBar(this);
        int volume;
        vol.setMax(maxVolume);
        volume = getCurrentVolume();
        vol.setProgress(volume);
        vol.setLayoutParams(volparams);
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean touch) {
                setVolume(progress);
            }

            public void onStartTrackingTouch(SeekBar arg0) {
            }

            public void onStopTrackingTouch(SeekBar arg0) {
            }
        });
        layout.addView(vol);
        return layout;
    }

    private void setVolume(int volume) {
        if (am != null)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
    }

    private int getCurrentVolume() {
        int volumeTmp = 0;
        if (am != null)
            volumeTmp = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        return volumeTmp;
    }

    @Override
    public void onMachineStatusChanged(Machine machine, @NonNull MachineController.MachineStatus status, Object o) {
        switch (status) {
            case SaveFailed:
                LimboActivityCommon.promptPausedErrorVM(this, (String) o, viewListener);
                break;
            case SaveCompleted:
                LimboActivityCommon.promptPausedVM(this, viewListener);
                break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Config.OPEN_IMAGE_FILE_REQUEST_CODE
                || requestCode == Config.OPEN_IMAGE_FILE_ASF_REQUEST_CODE) {
            String file;
            if (requestCode == Config.OPEN_IMAGE_FILE_ASF_REQUEST_CODE) {
                file = FileUtils.getFileUriFromIntent(this, data, true);
            } else {
                if (drives != null)
                    drives.fileType = FileUtils.getFileTypeFromIntent(this, data);
                file = FileUtils.getFilePathFromIntent(this, data);
            }
            if (drives != null && file != null)
                drives.setDriveAttr(drives.fileType, file);
        } else if (requestCode == Config.OPEN_LOG_FILE_DIR_REQUEST_CODE
                || requestCode == Config.OPEN_LOG_FILE_DIR_ASF_REQUEST_CODE) {
            String file;
            if (requestCode == Config.OPEN_LOG_FILE_DIR_ASF_REQUEST_CODE) {
                file = FileUtils.getFileUriFromIntent(this, data, true);
            } else {
                file = FileUtils.getDirPathFromIntent(this, data);
            }
            if (file != null) {
                FileUtils.saveLogToFile(this, file);
            }
        }
    }

    @Override
    protected void onDestroy() {
        MachineController.getInstance().removeOnStatusChangeListener(this);
        super.onDestroy();
    }
}
