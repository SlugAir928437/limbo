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

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.limbo.emu.R;
import com.limbo.emu.files.FileUtils;
import com.limbo.emu.jni.AglDisplay;
import com.limbo.emu.keyboard.KeyboardUtils;
import com.limbo.emu.log.Logger;
import com.limbo.emu.machine.Machine;
import com.limbo.emu.machine.MachineAction;
import com.limbo.emu.machine.MachineController;
import com.limbo.emu.toast.ToastUtils;

/**
 * AGL（Android Graphics Layer）显示宿主 Activity。
 *
 * <p>选择 AGL 显示时，QEMU 以 {@code -display agl} 启动，画面由 AGL 后端直接
 * 绘制到本 Activity 的 SurfaceView 上（见 {@link AglDisplay}）。这是 gunyah/
 * gzvm 加速虚拟机唯一可用的显示方式：这两个加速器要求 QEMU 在本进程内以 root
 * 运行，而独立 root 子进程拿不到 App 的 Surface。
 *
 * <p>AGL 后端没有 SDL/GTK 那样的事件源，所以触摸、滚轮、键盘事件都在这里采集
 * 后直接推给 QEMU 的输入子系统。
 *
 * <p>与 GTK Activity 一样，界面不常驻工具栏：按返回键弹出运行期菜单
 * （{@code gtkactivitymenu.xml}）。
 */
public class LimboAglActivity extends AppCompatActivity
        implements MachineController.OnMachineStatusChangeListener {
    private static final String TAG = "LimboAglActivity";

    // 与 LimboSDLActivity.pendingStop/pendingPause 类似：主界面在 AGL 虚拟机
    // 运行期间需要把“停止/暂停”请求交给持有显示的 Activity 处理
    public static boolean pendingStop;
    public static boolean pendingPause;

    private ViewListener viewListener;
    private AudioManager am;
    protected int maxVolume;
    private boolean keyboardShown;
    public DrivesDialogBox drives = null;

    private AglSurfaceView surface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(createContentView());

        setupListeners();
        setupAudio();

        // 与 GTK 一样经 MachineService 启动虚拟机：这样在启动前就记录了退出码，
        // 原生崩溃（SIGSEGV 等）才能在下次进入应用时被检出并弹日志
        new Thread(() -> {
            try {
                Log.i(TAG, "Starting VM with AGL display");
                LimboApplication.getViewListener().onAction(MachineAction.START_VM, null);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to start VM: " + ex.getMessage());
            }
        }).start();

        // 主界面在虚拟机运行期间发起的停止/暂停请求
        checkPendingActions();
    }

    private View createContentView() {
        FrameLayout root = new FrameLayout(this);

        surface = new AglSurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 运行期菜单的锚点：屏幕右上角一个 1x1 的空视图，菜单从角上弹出
        View anchor = new View(this);
        FrameLayout.LayoutParams anchorParams = new FrameLayout.LayoutParams(1, 1);
        anchorParams.gravity = Gravity.TOP | Gravity.END;
        root.addView(anchor, anchorParams);
        surface.setMenuAnchor(anchor);

        return root;
    }

    private void setupListeners() {
        viewListener = LimboApplication.getViewListener();
        MachineController.getInstance().addOnStatusChangeListener(this);
    }

    // ============================================================
    // 运行期菜单（与 GTK/SDL 一致的动作集合）
    // ============================================================

    private void showVMMenu() {
        View anchor = surface.getMenuAnchor();
        PopupMenu popup = new PopupMenu(this, anchor != null ? anchor : surface);
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
                drives = new DrivesDialogBox(LimboAglActivity.this, R.style.Transparent, machine);
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
        if (keyboardShown) {
            KeyboardUtils.showKeyboard(this, LimboSDLActivity.toggleKeyboardFlag, surface);
        } else {
            KeyboardUtils.hideKeyboard(this, surface);
        }
    }

    @Override
    public void onBackPressed() {
        showVMMenu();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    // ============================================================
    // 键盘：硬件/软键盘统一转成 Linux 键码推给 QEMU
    // ============================================================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (sendKeyToVM(keyCode, event, true)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (sendKeyToVM(keyCode, event, false)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean sendKeyToVM(int keyCode, KeyEvent event, boolean down) {
        // 硬件键盘会带上 Linux 键码（KeyEvent.getScanCode()），软键盘没有，
        // 需要按 Android 键值转换
        int scanCode = event.getScanCode();
        if (scanCode <= 0) {
            scanCode = androidToLinuxKeyCode(keyCode);
        }
        if (scanCode <= 0) {
            return false;
        }
        AglDisplay.key(scanCode, down);
        return true;
    }

    /**
     * Android 键值（{@link KeyEvent#KEYCODE_A} 等）转 Linux 输入键码
     * （{@code KEY_*}，即 evdev 键码）。QEMU 用 {@code qemu_input_linux_to_qcode()}
     * 消费它。只覆盖常用键，未列出的键返回 0（忽略）。
     */
    private static int androidToLinuxKeyCode(int keyCode) {
        switch (keyCode) {
            // 字母
            case KeyEvent.KEYCODE_A: return 30;
            case KeyEvent.KEYCODE_B: return 48;
            case KeyEvent.KEYCODE_C: return 46;
            case KeyEvent.KEYCODE_D: return 32;
            case KeyEvent.KEYCODE_E: return 18;
            case KeyEvent.KEYCODE_F: return 33;
            case KeyEvent.KEYCODE_G: return 34;
            case KeyEvent.KEYCODE_H: return 35;
            case KeyEvent.KEYCODE_I: return 23;
            case KeyEvent.KEYCODE_J: return 36;
            case KeyEvent.KEYCODE_K: return 37;
            case KeyEvent.KEYCODE_L: return 38;
            case KeyEvent.KEYCODE_M: return 50;
            case KeyEvent.KEYCODE_N: return 49;
            case KeyEvent.KEYCODE_O: return 24;
            case KeyEvent.KEYCODE_P: return 25;
            case KeyEvent.KEYCODE_Q: return 16;
            case KeyEvent.KEYCODE_R: return 19;
            case KeyEvent.KEYCODE_S: return 31;
            case KeyEvent.KEYCODE_T: return 20;
            case KeyEvent.KEYCODE_U: return 22;
            case KeyEvent.KEYCODE_V: return 47;
            case KeyEvent.KEYCODE_W: return 17;
            case KeyEvent.KEYCODE_X: return 45;
            case KeyEvent.KEYCODE_Y: return 21;
            case KeyEvent.KEYCODE_Z: return 44;
            // 数字
            case KeyEvent.KEYCODE_0: return 11;
            case KeyEvent.KEYCODE_1: return 2;
            case KeyEvent.KEYCODE_2: return 3;
            case KeyEvent.KEYCODE_3: return 4;
            case KeyEvent.KEYCODE_4: return 5;
            case KeyEvent.KEYCODE_5: return 6;
            case KeyEvent.KEYCODE_6: return 7;
            case KeyEvent.KEYCODE_7: return 8;
            case KeyEvent.KEYCODE_8: return 9;
            case KeyEvent.KEYCODE_9: return 10;
            // 符号
            case KeyEvent.KEYCODE_COMMA: return 51;
            case KeyEvent.KEYCODE_PERIOD: return 52;
            case KeyEvent.KEYCODE_MINUS: return 12;
            case KeyEvent.KEYCODE_EQUALS: return 13;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return 26;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return 27;
            case KeyEvent.KEYCODE_BACKSLASH: return 43;
            case KeyEvent.KEYCODE_SEMICOLON: return 39;
            case KeyEvent.KEYCODE_APOSTROPHE: return 40;
            case KeyEvent.KEYCODE_GRAVE: return 41;
            case KeyEvent.KEYCODE_SLASH: return 53;
            case KeyEvent.KEYCODE_STAR: return 55;
            case KeyEvent.KEYCODE_POUND: return 4;
            // 控制键
            case KeyEvent.KEYCODE_ESCAPE: return 1;
            case KeyEvent.KEYCODE_ENTER: return 28;
            case KeyEvent.KEYCODE_DEL: return 14;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 111;
            case KeyEvent.KEYCODE_TAB: return 15;
            case KeyEvent.KEYCODE_SPACE: return 57;
            case KeyEvent.KEYCODE_CAPS_LOCK: return 58;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 42;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 54;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 29;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 97;
            case KeyEvent.KEYCODE_ALT_LEFT: return 56;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 100;
            case KeyEvent.KEYCODE_META_LEFT: return 125;
            case KeyEvent.KEYCODE_META_RIGHT: return 126;
            case KeyEvent.KEYCODE_NUM_LOCK: return 69;
            case KeyEvent.KEYCODE_SCROLL_LOCK: return 70;
            case KeyEvent.KEYCODE_SYSRQ: return 99;
            case KeyEvent.KEYCODE_BREAK: return 119;
            // 导航
            case KeyEvent.KEYCODE_DPAD_UP: return 103;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 108;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 105;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 106;
            case KeyEvent.KEYCODE_DPAD_CENTER: return 96;
            case KeyEvent.KEYCODE_MOVE_HOME: return 102;
            case KeyEvent.KEYCODE_MOVE_END: return 107;
            case KeyEvent.KEYCODE_PAGE_UP: return 104;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 109;
            case KeyEvent.KEYCODE_INSERT: return 110;
            // 功能键
            case KeyEvent.KEYCODE_F1: return 59;
            case KeyEvent.KEYCODE_F2: return 60;
            case KeyEvent.KEYCODE_F3: return 61;
            case KeyEvent.KEYCODE_F4: return 62;
            case KeyEvent.KEYCODE_F5: return 63;
            case KeyEvent.KEYCODE_F6: return 64;
            case KeyEvent.KEYCODE_F7: return 65;
            case KeyEvent.KEYCODE_F8: return 66;
            case KeyEvent.KEYCODE_F9: return 67;
            case KeyEvent.KEYCODE_F10: return 68;
            case KeyEvent.KEYCODE_F11: return 87;
            case KeyEvent.KEYCODE_F12: return 88;
            default: return 0;
        }
    }

    // ============================================================
    // 状态与工具
    // ============================================================

    private void checkPendingActions() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (pendingStop) {
                    pendingStop = false;
                    LimboActivityCommon.promptStopVM(LimboAglActivity.this, viewListener);
                } else if (pendingPause) {
                    pendingPause = false;
                    LimboActivityCommon.promptPause(LimboAglActivity.this, viewListener);
                }
            }
        }, 1000);
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
        if (surface != null) {
            surface.releaseSurface();
        }
        super.onDestroy();
    }

    protected void setupAudio() {
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
        vol.setMax(maxVolume);
        vol.setProgress(am != null ? am.getStreamVolume(AudioManager.STREAM_MUSIC) : 0);
        vol.setLayoutParams(volparams);
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean touch) {
                if (am != null)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
            }

            public void onStartTrackingTouch(SeekBar arg0) {
            }

            public void onStopTrackingTouch(SeekBar arg0) {
            }
        });
        layout.addView(vol);
        return layout;
    }

    /**
     * 承载 AGL 画面的 SurfaceView：Surface 生命周期直接映射到 AGL 窗口，触摸/
     * 滚轮事件转成 QEMU 输入事件。
     */
    private static final class AglSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
        private View menuAnchor;
        private boolean touchDown;
        private int pointerButtons;
        private float pointerX = Float.NaN;
        private float pointerY = Float.NaN;

        AglSurfaceView(Context context) {
            super(context);
            getHolder().addCallback(this);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setKeepScreenOn(true);
        }

        void setMenuAnchor(View anchor) {
            this.menuAnchor = anchor;
        }

        View getMenuAnchor() {
            return menuAnchor;
        }

        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            requestFocus();
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            Surface s = holder.getSurface();
            if (s != null && s.isValid()) {
                AglDisplay.setSurface(s, displayRefreshRate());
            }
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            releaseSurface();
        }

        void releaseSurface() {
            pointerButtons = 0;
            touchDown = false;
            AglDisplay.setSurface(null, 0);
        }

        /** 屏幕当前刷新率（Hz），取不到时返回 0 由后端决定。 */
        private float displayRefreshRate() {
            if (getDisplay() == null || getDisplay().getMode() == null) {
                return 0f;
            }
            return getDisplay().getMode().getRefreshRate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchDown = true;
                    requestFocus();
                    pointer(event, 1);
                    break;
                case MotionEvent.ACTION_MOVE:
                    pointer(event, touchDown ? 1 : 0);
                    break;
                case MotionEvent.ACTION_UP:
                    touchDown = false;
                    pointer(event, 0);
                    performClick();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    touchDown = false;
                    pointer(event, 0);
                    break;
                default:
                    break;
            }
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        @Override
        public boolean onGenericMotionEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_MOVE:
                case MotionEvent.ACTION_MOVE:
                    pointer(event, event.getButtonState());
                    return true;
                case MotionEvent.ACTION_SCROLL:
                    AglDisplay.scroll(
                            event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                            -event.getAxisValue(MotionEvent.AXIS_VSCROLL));
                    return true;
                default:
                    return super.onGenericMotionEvent(event);
            }
        }

        private void pointer(MotionEvent event, int buttons) {
            // AGL 后端按 Surface 的像素坐标换算客户机坐标，这里直接上报视图内坐标
            pointerX = event.getX();
            pointerY = event.getY();
            pointerButtons = buttons;
            AglDisplay.pointer(pointerX, pointerY, pointerButtons);
        }
    }
}
