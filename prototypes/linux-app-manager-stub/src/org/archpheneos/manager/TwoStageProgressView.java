package org.archpheneos.manager;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

final class TwoStageProgressView extends LinearLayout {
    private final TextView label;
    private final ProgressBar download;
    private final ProgressBar install;

    TwoStageProgressView(Context context, int primaryColor, int mutedColor) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(4), dp(5), dp(4), dp(5));
        label = new TextView(context);
        label.setTextSize(11);
        label.setTextColor(mutedColor);
        addView(label, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout stages = new LinearLayout(context);
        stages.setGravity(Gravity.CENTER_VERTICAL);
        download = progress(context, primaryColor);
        install = progress(context, primaryColor);
        LayoutParams first = new LayoutParams(0, dp(4), 1);
        first.rightMargin = dp(4);
        stages.addView(download, first);
        stages.addView(install, new LayoutParams(0, dp(4), 1));
        addView(stages, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
    }

    void setState(ApkUpdateInstaller.Phase phase, int percent, String status) {
        label.setText(status);
        if (phase == ApkUpdateInstaller.Phase.DOWNLOAD) {
            download.setProgress(percent);
            install.setProgress(0);
        } else if (phase == ApkUpdateInstaller.Phase.INSTALL) {
            download.setProgress(100);
            install.setProgress(percent);
        } else if (phase == ApkUpdateInstaller.Phase.COMPLETE) {
            download.setProgress(100);
            install.setProgress(100);
        }
    }

    private ProgressBar progress(Context context, int color) {
        ProgressBar value = new ProgressBar(context, null,
                android.R.attr.progressBarStyleHorizontal);
        value.setMax(100);
        value.setProgressTintList(ColorStateList.valueOf(color));
        value.setProgressBackgroundTintList(ColorStateList.valueOf(
                Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))));
        return value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}