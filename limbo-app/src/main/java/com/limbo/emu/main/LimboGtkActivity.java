package com.limbo.emu.main;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import com.limbo.emu.R;
import com.limbo.emu.files.FileUtils;
import com.limbo.emu.jni.LimboGtk;
import com.limbo.emu.jni.VMExecutor;
import com.limbo.emu.log.Logger;
import com.limbo.emu.machine.Machine;
import com.limbo.emu.machine.MachineAction;
import com.limbo.emu.machine.MachineController;
import com.limbo.emu.toast.ToastUtils;

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

    /** GTK 顶层 Surface 由 QEMU 建窗口后才出现，这里用有界重试等它就绪。 */
    private static final int REFRESH_RATE_RETRY_MS = 500;
    private static final int MAX_REFRESH_RATE_RETRIES = 30;

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

        // 恢复上次选择的可变刷新率（native 侧先缓存，等 QEMU 库加载后下发）；
        // GTK 顶层 Surface 稍后才由 QEMU 创建，等它就绪再把频率交给面板。
        applyRefreshRateWhenSurfaceReady();
    }

    // ============================================================
    // 可变刷新率（VRR）
    // ============================================================

    /**
     * 把当前设置解析成目标频率（Hz）并下发：QEMU 侧改 limbo_gtk_refresh_rate
     * （GTK 后端据此重算 dcl->update_interval），面板侧由 Surface.setFrameRate() 决定。
     */
    private void applyRefreshRateNow() {
        int hz = resolveRefreshRateHz();
        VMExecutor.setSdlRefreshRateHz(hz);
        RefreshRateUtils.applyToSurface(gtkSurface(), hz);
    }

    private void applyRefreshRateMode(int mode) {
        LimboSettingsManager.setSDLRefreshRate(this, mode);
        applyRefreshRateNow();
    }

    private int resolveRefreshRateHz() {
        return RefreshRateUtils.resolveHz(currentDisplay(),
                LimboSettingsManager.getSDLRefreshRate(this));
    }

    private Display currentDisplay() {
        ToplevelView view = getGtkView();
        return view != null ? view.getDisplay() : getWindow().getDecorView().getDisplay();
    }

    /** GTK 顶层 Surface，QEMU 创建窗口前为 null。 */
    private Surface gtkSurface() {
        ToplevelView view = getGtkView();
        if (view == null || view.toplevel == null) {
            return null;
        }
        return view.toplevel.getHolder().getSurface();
    }

    /**
     * 有界轮询等待 GTK 顶层 Surface 就绪后再应用刷新率（Surface 由 QEMU 建窗口时
     * 才创建，onCreate 阶段通常还没好）。
     */
    private void applyRefreshRateWhenSurfaceReady() {
        final ToplevelView anchor = getGtkView();
        if (anchor == null) {
            return;
        }
        anchor.postDelayed(new Runnable() {
            private int attempts;

            @Override
            public void run() {
                if (gtkSurface() != null || attempts++ >= MAX_REFRESH_RATE_RETRIES) {
                    applyRefreshRateNow();
                    return;
                }
                anchor.postDelayed(this, REFRESH_RATE_RETRY_MS);
            }
        }, REFRESH_RATE_RETRY_MS);
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
        RefreshRateUtils.syncMenu(menu, LimboSettingsManager.getSDLRefreshRate(this));
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
        } else {
            Integer refreshMode = RefreshRateUtils.menuMode(id);
            if (refreshMode != null) {
                applyRefreshRateMode(refreshMode);
            }
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
