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
package com.freshingair.limbo.main;

import android.annotation.SuppressLint;
import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.freshingair.limbo.R;
import com.freshingair.limbo.network.NetworkUtils;
import com.freshingair.limbo.toast.ToastUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LimboSettingsManager extends PreferenceActivity {
    private static final String TAG = "LimboSettingsManager";

    // DNS server
    static String getDNSServer(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString("dnsServer", Config.defaultDNSServer);
    }

    public static void setDNSServer(Context context, String dnsServer) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putString("dnsServer", dnsServer);
        edit.apply();
    }

    // Screen
    public static int getOrientationSetting(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return Integer.parseInt(prefs.getString("orientationPref", "0"));
    }

    public static boolean getAlwaysShowMenuToolbar(Context activity) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        return prefs.getBoolean("AlwaysShowMenuToolbar", false);
    }

    public static boolean getFullscreen(Context activity) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        return prefs.getBoolean("ShowFullscreen", true);
    }

    // updates
    public static boolean getPromptUpdateVersion(Context context) {
        if(!Config.enableSoftwareUpdates)
            return false;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("updateVersionPrompt", Config.defaultCheckNewVersion);
    }

    public static void setPromptUpdateVersion(Context context, boolean value) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putBoolean("updateVersionPrompt", value);
        edit.apply();
    }

    // advanced
    public static boolean getPrio(Context activity) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        return prefs.getBoolean("HighPrio", false);
    }

    // 看门狗绑定的物理核心（仅 KVM）：CPU 列表如 "4-7"，空串表示自动选簇
    public static String getWatchdogCpuAffinity(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString("watchdogCpuAffinity", "");
    }

    /**
     * 按设备实际在线 CPU 填充「看门狗绑定核心」的选项。
     *
     * <p>分组方式与 native 侧选簇一致：优先 cpufreq/related_cpus（一个 cpufreq
     * policy 就是一个物理簇），其次 cpu_capacity（EAS 算力相同即同簇），都读不到
     * 就按单个 CPU 列出。值为 CPU 列表（如 "0-3"），空串表示自动。
     */
    private void setupCpuAffinityPreference() {
        Preference preference = getPreferenceManager().findPreference("watchdogCpuAffinity");
        if (!(preference instanceof ListPreference))
            return;
        final ListPreference list = (ListPreference) preference;

        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        labels.add(getString(R.string.WatchdogCpuAffinityAuto));
        values.add("");

        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int cpu : readOnlineCpus()) {
            String key = readCpuGroupKey(cpu);
            List<Integer> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(cpu);
        }
        for (List<Integer> group : groups.values()) {
            String value = formatCpuList(group);
            labels.add(getString(R.string.WatchdogCpuAffinityGroup, value));
            values.add(value);
        }

        // 保存的可能是别的设备的核心号：保留成自定义条目，别让用户的选择静默丢失
        String current = getWatchdogCpuAffinity(this);
        if (current != null && !current.isEmpty() && !values.contains(current)) {
            labels.add(getString(R.string.WatchdogCpuAffinityCustom, current));
            values.add(current);
        }

        list.setEntries(labels.toArray(new CharSequence[0]));
        list.setEntryValues(values.toArray(new CharSequence[0]));
        list.setOnPreferenceChangeListener((pref, newValue) -> {
            updateCpuAffinitySummary(list, (String) newValue);
            return true;
        });
        updateCpuAffinitySummary(list, list.getValue());
    }

    private void updateCpuAffinitySummary(ListPreference list, String value) {
        if (value == null)
            value = "";
        CharSequence[] entries = list.getEntries();
        int index = list.findIndexOfValue(value);
        CharSequence label = index >= 0 && entries != null ? entries[index] : value;
        list.setSummary(getString(R.string.WatchdogCpuAffinitySummary, label));
    }

    /** 读 /sys/devices/system/cpu/online（"0-3,5" -> [0,1,2,3,5]）。 */
    private static List<Integer> readOnlineCpus() {
        List<Integer> cpus = new ArrayList<>();
        String text = readSysfsText("/sys/devices/system/cpu/online");
        if (text == null)
            return cpus;
        for (String part : text.trim().split(",")) {
            try {
                int dash = part.indexOf('-');
                if (dash > 0) {
                    int first = Integer.parseInt(part.substring(0, dash).trim());
                    int last = Integer.parseInt(part.substring(dash + 1).trim());
                    for (int cpu = first; cpu <= last && cpu < 64; cpu++)
                        cpus.add(cpu);
                } else {
                    int cpu = Integer.parseInt(part.trim());
                    if (cpu >= 0 && cpu < 64)
                        cpus.add(cpu);
                }
            } catch (NumberFormatException ignored) {
                // 忽略无法解析的项，不影响其他 CPU
            }
        }
        return cpus;
    }

    /** 与 native 侧 limbo_cpu_cluster_key() 一致的分组键。 */
    private static String readCpuGroupKey(int cpu) {
        String related = readSysfsText(
                "/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/related_cpus");
        if (related != null && !related.trim().isEmpty())
            return "policy:" + related.trim();
        String capacity = readSysfsText(
                "/sys/devices/system/cpu/cpu" + cpu + "/cpu_capacity");
        if (capacity != null && !capacity.trim().isEmpty())
            return "capacity:" + capacity.trim();
        return "cpu:" + cpu;
    }

    private static String readSysfsText(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    /** [0,1,2,5] -> "0-2,5" */
    private static String formatCpuList(List<Integer> cpus) {
        Collections.sort(cpus);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cpus.size(); ) {
            int first = cpus.get(i);
            int last = first;
            while (i + 1 < cpus.size() && cpus.get(i + 1) == last + 1)
                last = cpus.get(++i);
            if (sb.length() > 0)
                sb.append(',');
            sb.append(first);
            if (last != first)
                sb.append('-').append(last);
            i++;
        }
        return sb.toString();
    }

    // files
    public static boolean getEnableLegacyFileManager(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("EnableLegacyFileManager", false);
    }

    public static String getLastDir(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString("lastDir", null);
    }

    public static void setLastDir(Context context, String imagesPath) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putString("lastDir", imagesPath);
        edit.apply();
    }

    public static String getImagesDir(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString("imagesDir", null);
    }

    public static void setImagesDir(Context context, String imagesPath) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putString("imagesDir", imagesPath);
        edit.apply();
    }

    public static String getExportDir(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString("exportDir", null);
    }

    public static void setExportDir(Context context, String imagesPath) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putString("exportDir", imagesPath);
        edit.apply();
    }

    public static String getSharedDir(Context context) {
        String lastDir = Environment.getExternalStorageDirectory().getPath();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString("sharedDir", lastDir);
    }

    public static void setSharedDir(Context context, String lastDir) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putString("sharedDir", lastDir);
        edit.apply();
    }

    // exit code
    public static int getExitCode(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getInt("exitCode", Config.EXIT_SUCCESS);
    }

    //XXX: we need to make sure this gets written to preferences right before we call System.exit()
    // so we need to use commit instead of apply
    @SuppressLint("ApplySharedPref")
    public static void setExitCode(Context context, int exitCode) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putInt("exitCode", exitCode);
        edit.commit();
    }

    public static boolean isFirstLaunch(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("firstTime" + LimboApplication.getLimboVersionString(), true);
    }

    public static void setFirstLaunch(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putBoolean("firstTime" + LimboApplication.getLimboVersionString(), false);
        edit.apply();
    }

    // Key Mapper
    public static Set<String> getKeyMappers(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> set = new HashSet<>();
        return prefs.getStringSet("keyMappers", set);
    }

    public static void setKeyMappers(Context context, Set<String> keyMappers) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putStringSet("keyMappers", keyMappers);
        edit.apply();
    }

    public static int getKeyMapperSize(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String sizeStr = prefs.getString("keyMapperSize", "3");
        return Integer.parseInt(sizeStr);
    }

    // SDL 显示方式（缩放模式）：0=拉伸至全屏 1=等比缩放（默认）2=原始 1:1
    public static int getSDLScaleMode(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getInt("sdlScaleMode", Config.SDL_SCALE_ASPECT);
    }

    public static void setSDLScaleMode(Context context, int value) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putInt("sdlScaleMode", value);
        edit.apply();
    }

    // SDL 可变刷新率（VRR）：0=关闭 -1=跟随屏幕 >0=目标频率(Hz)
    public static int getSDLRefreshRate(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getInt("sdlRefreshRate", Config.SDL_REFRESH_OFF);
    }

    /**
     * 显卡渲染后端：0 = 系统 GLES（默认），1 = ANGLE。
     *
     * <p>对应 QEMU/virglrenderer 侧 libepoxy 的 EGL/GL 库选择，见
     * VMExecutor#setVirglRenderBackend 与 patches/epoxy-android-angle-backend.patch。
     */
    public static int getVirglRenderBackend(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        // settings.xml 里是 ListPreference，存的是字符串（"0"/"1"），这里按数值解析
        String value = prefs.getString("virglRenderBackend", "0");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void setVirglRenderBackend(Context context, int value) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putString("virglRenderBackend", String.valueOf(value));
        edit.apply();
    }

    public static void setSDLRefreshRate(Context context, int value) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putInt("sdlRefreshRate", value);
        edit.apply();
    }

    // VNC
    public static boolean getVNCEnablePassword(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("enableVNCPassword", false);
    }

    public static String getVNCPass(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString("vncPass", "");
    }

    public static void setVNCPass(Context context, String value) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putString("vncPass", value);
        edit.apply();
    }

    public static boolean getEnableExternalVNC(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("enableExternalVNC", false);
    }

    // SPICE
    public static boolean getSPICEEnablePassword(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("enableSPICEPassword", false);
    }

    public static String getSPICEPass(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString("spicePass", "");
    }

    public static void setSPICEPass(Context context, String value) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putString("spicePass", value);
        edit.apply();
    }

    public static boolean getEnableExternalSPICE(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("enableExternalSPICE", false);
    }

    // QMP
    public static boolean getEnableQmp(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("enableQMP", true);
    }

    public static boolean getEnableExternalQMP(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("enableExternalQMP", false);
    }

    public static boolean getImmersiveMode(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("immersiveMode", false);
    }

    public static int getKeyPressDelay(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String sizeStr = prefs.getString("keyPressDelay", "100");
        return Integer.parseInt(sizeStr);
    }

    public static int getMouseButtonDelay(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String sizeStr = prefs.getString("mouseButtonDelay", "100");
        return Integer.parseInt(sizeStr);
    }

    public static boolean getEnableAaudio(Context activity) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        return prefs.getBoolean("enableAaudio", false);
    }

    public static String getDiskCache(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString("diskCachePref", context.getString(R.string.Default));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent data = new Intent();
        setResult(Config.SETTINGS_RETURN_CODE, data);
        addPrefs();
        setupCpuAffinityPreference();
        getPreferenceManager().findPreference("enableVNCPassword").setOnPreferenceChangeListener(
                (preference, newValue) -> {
                    if ((boolean) newValue)
                        promptVNCPass(LimboSettingsManager.this);
                    return true;
                });
        getPreferenceManager().findPreference("vncPass").setOnPreferenceClickListener(
                preference -> {
                    promptVNCPass(LimboSettingsManager.this);
                    return true;
                });
        getPreferenceManager().findPreference("enableSPICEPassword").setOnPreferenceChangeListener(
                (preference, newValue) -> {
                    if ((boolean) newValue)
                        promptSPICEPass(LimboSettingsManager.this);
                    return true;
                });
        getPreferenceManager().findPreference("spicePass").setOnPreferenceClickListener(
                preference -> {
                    promptSPICEPass(LimboSettingsManager.this);
                    return true;
                });

    }

    public void addPrefs() {
        addPreferencesFromResource(R.xml.settings);
        if(Config.enableSoftwareUpdates)
            addPreferencesFromResource(R.xml.software_updates);
        if(Config.enableImmersiveMode)
            addPreferencesFromResource(R.xml.immersive);
        if (Build.VERSION.SDK_INT >= 26)
            addPreferencesFromResource(R.xml.aaudio);
    }

    public void promptVNCPass(final Activity activity) {
        promptConsolePass(activity, R.string.VNCPassword,
                getString(R.string.vncServer) + ": " + NetworkUtils.getVNCAddress(activity)
                        + ":" + Config.defaultVNCPort + "\n" + getString(R.string.externalVNCWarning),
                value -> setVNCPass(activity, value));
    }

    public void promptSPICEPass(final Activity activity) {
        promptConsolePass(activity, R.string.SPICEPassword,
                getString(R.string.spiceServer) + ": " + NetworkUtils.getSPICEAddress(activity)
                        + ":" + Config.defaultSPICEPort + "\n" + getString(R.string.externalSPICEWarning),
                value -> setSPICEPass(activity, value));
    }

    /**
     * 显示远程控制台（VNC/SPICE）的密码输入对话框。两者的交互完全一样，
     * 只有标题、服务器地址与保存位置不同。
     */
    private void promptConsolePass(final Activity activity, int titleRes, String serverInfo,
                                   final java.util.function.Consumer<String> setter) {
        final AlertDialog alertDialog;
        alertDialog = new AlertDialog.Builder(activity).create();
        alertDialog.setTitle(getString(titleRes));

        TextView textView = new TextView(activity);
        textView.setVisibility(View.VISIBLE);
        textView.setPadding(20, 20, 20, 20);
        textView.setText(serverInfo);

        final EditText passwdView = new EditText(activity);
        passwdView.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        passwdView.setSelection(passwdView.getText().length());
        passwdView.setHint(R.string.Password);
        passwdView.setEnabled(true);
        passwdView.setVisibility(View.VISIBLE);
        passwdView.setSingleLine();

        LinearLayout mLayout = new LinearLayout(this);
        mLayout.setPadding(20, 20, 20, 20);
        mLayout.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams textViewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mLayout.addView(textView, textViewParams);

        LinearLayout.LayoutParams passwordViewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mLayout.addView(passwdView, passwordViewParams);

        alertDialog.setView(mLayout);
        passwdView.setTag(false);
        passwdView.setTransformationMethod(new PasswordTransformationMethod());
        passwdView.setSelection(passwdView.getText().length());

        alertDialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(android.R.string.ok), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (passwdView.getText().toString().trim().isEmpty()) {
                    ToastUtils.toastShort(getApplicationContext(), getString(R.string.passwordCannotBeEmpty));
                } else {
                    setter.accept(passwdView.getText().toString());
                }
            }
        });
        alertDialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.Cancel), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                setter.accept(null);
            }
        });


        alertDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                setter.accept(null);
            }
        });
        alertDialog.show();

        try {
            Button passwordButton = alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            passwordButton.setVisibility(View.VISIBLE);
            passwordButton.setBackgroundResource(android.R.drawable.ic_menu_view);
            passwordButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if ((boolean) passwdView.getTag()) {
                        passwdView.setTransformationMethod(new PasswordTransformationMethod());
                    } else
                        passwdView.setTransformationMethod(new HideReturnsTransformationMethod());
                    passwdView.setTag(!(boolean) passwdView.getTag());
                    passwdView.setSelection(passwdView.getText().length());
                }
            });
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }


    public static boolean getPreventMouseOutOfBounds(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("preventMouseOutOfBounds", false);
    }

}
