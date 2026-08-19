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
package com.max2idea.android.limbo.dialog;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class DialogUtils {
    private static final String TAG = "DialogUtils";

    public static void UIAlert(Activity activity, String title, String body) {

        final AlertDialog alertDialog;
        alertDialog = new MaterialAlertDialogBuilder(activity).create();
        alertDialog.setTitle(title);
        TextView textView = new TextView(activity);
        textView.setPadding(20, 20, 20, 20);
        textView.setText(body);
        ScrollView view = new ScrollView(activity);
        view.addView(textView);
        alertDialog.setView(view);
        alertDialog.setButton(DialogInterface.BUTTON_POSITIVE,
                activity.getResources().getString(android.R.string.ok),
                (dialog, which) -> alertDialog.dismiss());
        alertDialog.show();
    }

    public static void UIAlert(Activity activity, String title, String body, int textSize, boolean cancelable,
                               String button1title, DialogInterface.OnClickListener button1Listener,
                               String button2title, DialogInterface.OnClickListener button2Listener,
                               String button3title, DialogInterface.OnClickListener button3Listener
    ) {

        AlertDialog alertDialog;
        alertDialog = new MaterialAlertDialogBuilder(activity).create();
        alertDialog.setTitle(title);
        alertDialog.setCanceledOnTouchOutside(cancelable);
        TextView textView = new TextView(activity);
        textView.setPadding(20, 20, 20, 20);
        textView.setText(body);
        if (textSize > 0)
            textView.setTextSize(textSize);
        ScrollView view = new ScrollView(activity);
        view.addView(textView);
        alertDialog.setView(view);
        if (button1title != null)
            alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, button1title, button1Listener);
        if (button2title != null)
            alertDialog.setButton(AlertDialog.BUTTON_NEGATIVE, button2title, button2Listener);
        if (button3title != null)
            alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, button3title, button3Listener);
        alertDialog.show();
    }
}
