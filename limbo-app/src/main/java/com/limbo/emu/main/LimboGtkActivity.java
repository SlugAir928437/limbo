package com.limbo.emu.main;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
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
    private boolean backKeyDown;
    private PopupMenu vmMenu;
    private View menuAnchor;
    private GtkImeView imeView;
    private InputMethodManager imm;
    private boolean imeWasVisible;
    public DrivesDialogBox drives = null;

    @Override
    protected View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // 运行期菜单的锚点：屏幕右上角一个 1x1 的空视图。直接用铺满全屏的
        // ToplevelView 当锚点会把 PopupMenu 定位到屏幕外（看不见）。
        menuAnchor = new View(this);
        LinearLayout.LayoutParams anchorParams =
                new LinearLayout.LayoutParams(1, 1);
        anchorParams.gravity = Gravity.END;
        root.addView(menuAnchor, anchorParams);

        // GTK 窗口没有可编辑控件，GDK 的 InputConnection 永远不会激活，
        // 系统输入法无法挂到 GTK Surface 上。用这个 1x1 的不可见文本编辑器
        // 承接软键盘输入，再转成按键事件喂给 GTK。
        imeView = new GtkImeView(this);
        imeView.setAlpha(0.01f);
        root.addView(imeView, new LinearLayout.LayoutParams(1, 1));

        root.addView(getGtkView(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // The ToplevelView positions the GTK surface below the system bars
        // itself, so here we only consume the top system-bar inset.
        root.setOnApplyWindowInsetsListener((v, windowInsets) -> {
            WindowInsetsCompat compat = WindowInsetsCompat.toWindowInsetsCompat(windowInsets, v);
            Insets bars = compat.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            // 输入法被系统/用户收起后，把焦点还给 GTK 画面并更新状态。
            int imeBottom = compat.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            if (imeBottom > 0) {
                imeWasVisible = true;
            } else if (keyboardShown && imeWasVisible) {
                imeWasVisible = false;
                keyboardShown = false;
                ToplevelView gtk = getGtkView();
                if (gtk != null && gtk.toplevel != null) {
                    gtk.toplevel.post(gtk.toplevel::requestFocus);
                }
            }
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

        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
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
        View anchor = menuAnchor != null ? menuAnchor : getGtkView();
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        getMenuInflater().inflate(R.menu.gtkactivitymenu, menu);
        Machine machine = MachineController.getInstance().getMachine();
        if (machine == null || machine.getSoundCard() == null) {
            menu.removeItem(R.id.itemVolume);
        }
        RefreshRateUtils.syncMenu(menu, LimboSettingsManager.getSDLRefreshRate(this));
        popup.setOnMenuItemClickListener(this::onMenuItemSelected);
        popup.setOnDismissListener(p -> vmMenu = null);
        popup.show();
        vmMenu = popup;
    }

    /**
     * 返回键统一在这里处理：软键盘展开时先收起键盘，菜单已展开时收起菜单，
     * 否则弹出运行期菜单。
     */
    private void handleBack() {
        if (keyboardShown) {
            keyboardShown = false;
            hideImeKeyboard();
            return;
        }
        if (vmMenu != null) {
            vmMenu.dismiss();
            return;
        }
        showVMMenu();
    }

    /**
     * 在 Activity 分发阶段最先截获返回键。
     *
     * <p>GTK 的 SurfaceView 持有输入焦点，返回键会被它当作 Escape 交给 GTK
     * （GTK 的窗口/菜单会消费该按键），导致活动层的 {@code onBackPressed()}
     * 收不到事件、运行期菜单打不开。这里直接消费返回键，松手时再弹菜单。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                backKeyDown = true;
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean handled = backKeyDown && !event.isCanceled();
                backKeyDown = false;
                if (handled) {
                    handleBack();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
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
        if (keyboardShown) {
            showImeKeyboard();
        } else {
            hideImeKeyboard();
        }
    }

    private void showImeKeyboard() {
        if (imeView == null) {
            return;
        }
        imeView.requestFocus();
        imeView.post(() -> {
            if (imm != null) {
                imm.showSoftInput(imeView, InputMethodManager.SHOW_FORCED);
            }
        });
    }

    private void hideImeKeyboard() {
        if (imeView == null) {
            return;
        }
        if (imm != null) {
            imm.hideSoftInputFromWindow(imeView.getWindowToken(), 0);
        }
        // 把按键焦点还给 GTK 画面，否则硬件键盘事件会继续落在 IME 视图上。
        ToplevelView view = getGtkView();
        if (view != null && view.toplevel != null) {
            view.toplevel.requestFocus();
        }
    }

    /**
     * 把软键盘提交的文本/按键转成 Android KeyEvent，再交给 GTK Surface 的
     * 按键通道（notifyKeyEvent），最终由 QEMU 送入客户机。
     */
    private void sendTextToGtk(CharSequence text) {
        if (text == null) {
            return;
        }
        ToplevelView view = getGtkView();
        if (view == null || view.toplevel == null) {
            return;
        }
        KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                sendKeyToGtk(KeyEvent.KEYCODE_ENTER, 0);
                continue;
            }
            KeyEvent[] events = map.getEvents(new char[]{c});
            if (events == null) {
                continue;
            }
            for (KeyEvent event : events) {
                long now = SystemClock.uptimeMillis();
                KeyEvent copy = new KeyEvent(now, now, event.getAction(),
                        event.getKeyCode(), 0, event.getMetaState());
                if (copy.getAction() == KeyEvent.ACTION_DOWN) {
                    view.toplevel.onKeyDown(copy.getKeyCode(), copy);
                } else if (copy.getAction() == KeyEvent.ACTION_UP) {
                    view.toplevel.onKeyUp(copy.getKeyCode(), copy);
                }
            }
        }
    }

    private void sendKeyToGtk(int keyCode, int metaState) {
        ToplevelView view = getGtkView();
        if (view == null || view.toplevel == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        view.toplevel.onKeyDown(keyCode,
                new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState));
        view.toplevel.onKeyUp(keyCode,
                new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState));
    }

    /**
     * 不可见的文本编辑器：只为让系统输入法有可挂载的 InputConnection。
     * IME 提交的字符和按键都在这里转给 GTK。
     */
    private final class GtkImeView extends View {
        GtkImeView(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return true;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_FLAG_NO_FULLSCREEN
                    | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
            return new BaseInputConnection(this, true) {
                @Override
                public boolean commitText(CharSequence text, int newCursorPosition) {
                    sendTextToGtk(text);
                    return true;
                }

                @Override
                public boolean setComposingText(CharSequence text, int newCursorPosition) {
                    // 禁止联想/组合输入，直接按已提交文本处理。
                    return true;
                }

                @Override
                public boolean sendKeyEvent(KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        sendKeyToGtk(event.getKeyCode(), event.getMetaState());
                    }
                    return true;
                }

                @Override
                public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                    for (int i = 0; i < beforeLength; i++) {
                        sendKeyToGtk(KeyEvent.KEYCODE_DEL, 0);
                    }
                    return true;
                }

                @Override
                public boolean performEditorAction(int actionCode) {
                    sendKeyToGtk(KeyEvent.KEYCODE_ENTER, 0);
                    return true;
                }
            };
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                return false;
            }
            sendKeyToGtk(keyCode, event.getMetaState());
            return true;
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            return keyCode != KeyEvent.KEYCODE_BACK;
        }
    }

    @Override
    public void onBackPressed() {
        // Gesture/legacy back also routes here; the physical back key is
        // intercepted in dispatchKeyEvent() before GTK can consume it.
        handleBack();
    }

    @Override
    public void postWindowConfiguration(int color, boolean fullscreen) {
        // GTK 上报的窗口背景色来自 GTK 主题（默认浅色）；显示区域统一用黑色底色。
        super.postWindowConfiguration(Color.BLACK, fullscreen);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 父类在重新获得窗口焦点时会把焦点交还给 GTK Surface；软键盘展开期间
        // 必须保持 IME 视图的焦点，否则输入法会立刻失去输入目标而收起。
        if (hasFocus && keyboardShown && imeView != null) {
            imeView.post(imeView::requestFocus);
        }
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
