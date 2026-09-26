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
package com.limbo.emu.updates;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.Log;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.king.app.updater.AppUpdater;
import com.limbo.emu.R;
import com.limbo.emu.main.Config;
import com.limbo.emu.main.LimboApplication;
import com.limbo.emu.main.LimboSettingsManager;
import com.limbo.emu.network.NetworkUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.noties.markwon.Markwon;

/**
 * Software Update notifier. Resolves the latest GitHub release (tag + APK asset),
 * and delegates the APK download/install to the AppUpdater library.
 */
public class UpdateChecker {
    private static final String TAG = "UpdateChecker";
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    public static void checkNewVersion(final Activity activity) {
        if (!LimboSettingsManager.getPromptUpdateVersion(activity)) {
            return;
        }
        try {
            byte[] data = NetworkUtils.getContentFromUrl(Config.latestReleaseApi);
            JSONObject release = new JSONObject(new String(data));

            String tagName = release.optString("tag_name", "");
            int[] remote = parseSemVer(tagName);
            if (remote == null || !isNewer(remote)) {
                return;
            }

            String apkUrl = findApkUrl(release);
            if (TextUtils.isEmpty(apkUrl)) {
                Log.w(TAG, "No APK asset found in latest release");
                return;
            }

            String versionName = release.optString("name", tagName);
            final String changelog = release.optString("body", "");
            final String finalUrl = apkUrl;
            final String finalVersionName = TextUtils.isEmpty(versionName) ? tagName : versionName;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    promptNewVersion(activity, finalUrl, finalVersionName, changelog);
                }
            });
        } catch (Exception ex) {
            Log.w(TAG, "Could not check for new version: " + ex.getMessage());
            if (Config.debug)
                ex.printStackTrace();
        }
    }

    /** Pick the first .apk asset; prefer the one containing Config.apkAssetKeyword when set. */
    private static String findApkUrl(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        String fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            String name = asset.optString("name", "");
            if (!name.toLowerCase().endsWith(".apk")) {
                continue;
            }
            String url = asset.optString("browser_download_url", "");
            if (url.isEmpty()) {
                continue;
            }
            if (fallback == null) {
                fallback = url;
            }
            if (Config.apkAssetKeyword.isEmpty() || name.contains(Config.apkAssetKeyword)) {
                return url;
            }
        }
        return fallback;
    }

    /** Extract major.minor.patch from a release tag such as "v1.2.3". */
    private static int[] parseSemVer(String tag) {
        if (TextUtils.isEmpty(tag)) {
            return null;
        }
        Matcher m = VERSION_PATTERN.matcher(tag);
        if (!m.find()) {
            return null;
        }
        int maj = Integer.parseInt(m.group(1));
        int min = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        int mic = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        return new int[]{maj, min, mic};
    }

    /** Compare the remote tag against the local versionCode (encoded major*10000+minor*100+patch). */
    private static boolean isNewer(int[] remote) {
        int localVersion = LimboApplication.getLimboVersion();
        int[] local = {localVersion / 10000, (localVersion % 10000) / 100, localVersion % 100};
        if (remote[0] != local[0]) return remote[0] > local[0];
        if (remote[1] != local[1]) return remote[1] > local[1];
        return remote[2] > local[2];
    }

    public static void promptNewVersion(Context context, String apkUrl, String versionName, String changelog) {
        try {
            String content = TextUtils.isEmpty(changelog)
                    ? context.getString(R.string.NewVersion)
                    : changelog;
            Spanned markwon = Markwon.create(context).toMarkdown(content);
            TextView textView = new TextView(context);
            textView.setText(markwon);
            new MaterialAlertDialogBuilder(context)
                    .setTitle(context.getString(R.string.NewVersion) + " v" + versionName)
                    .setView(textView)
                    .setPositiveButton(R.string.GenNewVersion, (dialog, which) ->
                            startDownload(context, apkUrl))
                    .setNegativeButton(R.string.DoNotShowAgain, (dialog, which) ->
                            LimboSettingsManager.setPromptUpdateVersion(context, false))
                    .show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void startDownload(Context context, String apkUrl) {
        try {
            new AppUpdater.Builder(context)
                    .setUrl(apkUrl)
                    .setFilename("limbo-update.apk")
                    .setShowNotification(true)
                    .setShowPercentage(true)
                    .setInstallApk(true)
                    .build()
                    .start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}