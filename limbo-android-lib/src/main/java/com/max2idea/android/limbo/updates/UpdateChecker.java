package com.max2idea.android.limbo.updates;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.text.Spanned;
import android.util.Log;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.limbo.emu.lib.R;
import com.max2idea.android.limbo.files.FileUtils;
import com.max2idea.android.limbo.main.Config;
import com.max2idea.android.limbo.main.LimboApplication;
import com.max2idea.android.limbo.main.LimboSettingsManager;
import com.max2idea.android.limbo.network.NetworkUtils;

import java.io.IOException;

import io.noties.markwon.Markwon;

/** Software Update notifier for checking if a new version is published.
  */
public class UpdateChecker {
    private static final String TAG = "UpdateChecker";

    public static void checkNewVersion(final Activity activity) {
        if (!LimboSettingsManager.getPromptUpdateVersion(activity)) {
            return;
        }

        try {
            byte[] streamData = NetworkUtils.getContentFromUrl(Config.newVersionLink);
            final String versionStr = new String(streamData).trim();
            float version = Float.parseFloat(versionStr);
            String versionName = getVersionName(versionStr);

            int versionCheck = (int) (version * 100);
            if (versionCheck > LimboApplication.getLimboVersion()) {
                final String finalVersionName = versionName;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        promptNewVersion(activity, finalVersionName);
                    }
                });
            }
        } catch (Exception ex) {
            Log.w(TAG, "Could not get new version: " + ex.getMessage());
            if (Config.debug)
                ex.printStackTrace();
        }
    }

    private static String getVersionName(String versionStr) {
        String[] versionSegments = versionStr.split("\\.");
        int maj = Integer.parseInt(versionSegments[0]) / 100;
        int min = Integer.parseInt(versionSegments[0]) % 100;
        int mic = 0;
        if (versionSegments.length > 1) {
            mic = Integer.parseInt(versionSegments[1]);
        }
        return maj + "." + min + "." + mic;
    }

    public static void promptNewVersion(Context context, String ChangelogLink) {
        try {
            Spanned markwon = Markwon.create(context)
                    .toMarkdown(FileUtils.downloadAndRead(ChangelogLink, context));
            TextView textView = new TextView(context);
            textView.setText(markwon);
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.NewVersion)
                    .setView(textView)
                    .setPositiveButton(R.string.GenNewVersion, (dialog, which) -> NetworkUtils.openURL(context, Config.downloadLink))
                    .setNegativeButton(R.string.DoNotShowAgain, (dialog, which) -> LimboSettingsManager.setPromptUpdateVersion(context, false))
                    .show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
