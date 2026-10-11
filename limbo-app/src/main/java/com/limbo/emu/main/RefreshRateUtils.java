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

import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;

import androidx.annotation.Nullable;

import com.limbo.emu.R;

/**
 * SDL / GTK / AGL 三种显示后端共用的「可变刷新率（VRR）」工具。
 *
 * <p>面板侧统一走 Android 11(API 30) 的 {@link Surface#setFrameRate(float, int, int)}
 * 把目标频率告知系统，由系统决定是否切换屏幕刷新率；QEMU 侧的输出节奏由各自的显示
 * 后端处理（SDL 的 limbo_sdl_refresh_rate、GTK 的 limbo_gtk_refresh_rate，以及 AGL
 * 后端本就支持的 refresh_rate），三者都由
 * {@link com.limbo.emu.jni.VMExecutor#setSdlRefreshRateHz(int)} 统一下发。
 *
 * <p>两端必须同时生效，缺一个就会「选了高刷却固定在 40Hz」：
 * <ul>
 *   <li>QEMU 侧：一旦显式选定了频率，dcl-&gt;update_interval 就严格按 1000/hz ms
 *       走，不能再回落到 30ms 的空闲节奏（gui_update() 取所有 listener 里最小的
 *       那一个，一次回落就把控制台定时器压到 33Hz）；</li>
 *   <li>面板侧：{@link #applyToSurface} 用 CHANGE_FRAME_RATE_ALWAYS 请求切换，
 *       且「跟随屏幕」取的是面板<b>支持的最高</b>刷新率（见 {@link #maxSupportedHz}），
 *       而不是 LTPO 面板空闲时正处的 40Hz 档。</li>
 * </ul>
 */
public final class RefreshRateUtils {
    private static final String TAG = "RefreshRateUtils";

    private RefreshRateUtils() {
    }

    /**
     * 把用户选择的模式解析成目标刷新率（Hz）。
     *
     * @param display 当前屏幕，可为 null（拿不到时「跟随屏幕」退化为 0）
     * @param mode    0=关闭 -1=跟随屏幕 &gt;0=固定频率(Hz)
     * @return 目标刷新率（Hz），0 表示不限制（交给系统/后端）
     */
    public static int resolveHz(@Nullable Display display, int mode) {
        if (mode == Config.SDL_REFRESH_OFF) {
            return 0;
        }
        if (mode > 0) {
            return mode;
        }
        // 跟随屏幕：用面板支持的最高刷新率，而不是可能正处于 40Hz 空闲档的当前模式
        return maxSupportedHz(display);
    }

    /**
     * 「跟随屏幕」模式下真正要用的目标频率：屏幕支持的最高刷新率。
     *
     * <p>不能直接拿 {@link Display#getMode()}（当前模式）：LTPO 面板在无输入时会
     * 主动降到 40Hz 甚至 30Hz，于是当前模式报的就是 40Hz —— 这时「跟随屏幕」
     * 反而把面板钉在 40Hz 上（用户看到的就是「选了高刷却固定在 40Hz」）。
     * {@link Display#getSupportedModes()} 里取最高的一档才是面板的真实能力，
     * 系统仍会按 Surface.setFrameRate() 的请求挑选最接近的实际模式。
     *
     * @param display 当前屏幕，可为 null（拿不到时退化为当前模式，再退化为 0）
     * @return 最高支持的刷新率（Hz），0 表示拿不到
     */
    public static int maxSupportedHz(@Nullable Display display) {
        if (display != null) {
            Display.Mode[] modes;
            try {
                modes = display.getSupportedModes();
            } catch (Throwable t) {
                Log.w(TAG, "getSupportedModes() 失败: " + t);
                modes = null;
            }
            if (modes != null && modes.length > 0) {
                float best = 0f;
                for (Display.Mode m : modes) {
                    if (m != null && m.getRefreshRate() > best) {
                        best = m.getRefreshRate();
                    }
                }
                if (best > 0f) {
                    return Math.round(best);
                }
            }
        }
        if (display != null && display.getMode() != null) {
            return Math.round(display.getMode().getRefreshRate());
        }
        return 0;
    }

    /**
     * 面板侧：把目标频率（Hz）交给系统，由系统决定是否切换面板刷新率。
     *
     * <p>0 表示关闭，按官方推荐把帧率设为 0 以清除此前的帧率设置。虚拟机画面属于
     * 游戏类非视频用途，因此兼容性参数用
     * {@link Surface#FRAME_RATE_COMPATIBILITY_DEFAULT}；低版本设备自动忽略。
     *
     * @param surface 目标 Surface，可为 null / 尚未就绪（直接忽略）
     * @param hz      目标刷新率（Hz），0 表示关闭
     */
    public static void applyToSurface(@Nullable Surface surface, int hz) {
        if (surface == null || !surface.isValid()) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        float requestedHz = hz > 0 ? hz : 0f;
        try {
            surface.setFrameRate(requestedHz,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ALWAYS);
        } catch (Throwable t) {
            Log.w(TAG, "setFrameRate(" + requestedHz + ") 失败: " + t);
        }
    }

    /**
     * 菜单项 id 转可变刷新率模式。
     *
     * @param itemId 被点击的菜单项 id
     * @return 对应的模式（0=关闭 -1=跟随屏幕 &gt;0=固定频率），不是刷新率菜单项时返回 null
     */
    @Nullable
    public static Integer menuMode(int itemId) {
        if (itemId == R.id.itemRefreshOff) {
            return Config.SDL_REFRESH_OFF;
        }
        if (itemId == R.id.itemRefreshAuto) {
            return Config.SDL_REFRESH_AUTO;
        }
        if (itemId == R.id.itemRefresh60) {
            return Config.SDL_REFRESH_RATES[0];
        }
        if (itemId == R.id.itemRefresh90) {
            return Config.SDL_REFRESH_RATES[1];
        }
        if (itemId == R.id.itemRefresh120) {
            return Config.SDL_REFRESH_RATES[2];
        }
        return null;
    }

    /**
     * 同步「可变刷新率」子菜单的选中项，对应当前生效的模式。
     *
     * @param menu 已 inflate 出刷新率子菜单的菜单，可为 null
     * @param mode 当前生效的模式
     */
    public static void syncMenu(@Nullable Menu menu, int mode) {
        if (menu == null) {
            return;
        }
        int[] ids = {R.id.itemRefreshOff, R.id.itemRefreshAuto,
                R.id.itemRefresh60, R.id.itemRefresh90, R.id.itemRefresh120};
        int[] modes = {Config.SDL_REFRESH_OFF, Config.SDL_REFRESH_AUTO,
                Config.SDL_REFRESH_RATES[0], Config.SDL_REFRESH_RATES[1],
                Config.SDL_REFRESH_RATES[2]};

        for (int i = 0; i < ids.length; i++) {
            MenuItem menuItem = menu.findItem(ids[i]);
            if (menuItem != null) {
                menuItem.setChecked(modes[i] == mode);
            }
        }
    }
}
