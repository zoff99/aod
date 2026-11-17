package com.zoffcc.applications.aod;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

/**
 * Shared light-color picker: a grid of large, labeled color circles plus an
 * "auto / default" option. Used from the settings screen (per-app overrides
 * on the notification strip) and from the app picker (long press on a row).
 */
public final class ColorDialogs
{
    public static final int[] PRESET_COLORS = {
            0xFF00E5FF, 0xFF00E676, 0xFFFF3D00, 0xFFFFC400,
            0xFFD500F9, 0xFF2979FF, 0xFFFFFFFF
    };

    public static final int[] PRESET_NAMES = {
            R.string.color_cyan, R.string.color_green, R.string.color_red,
            R.string.color_amber, R.string.color_purple, R.string.color_blue,
            R.string.color_white
    };

    public interface OnColorPicked
    {
        void onColorPicked(int color);
    }

    private ColorDialogs() {}

    /** Picks a per-app override; {@link Color#TRANSPARENT} clears it. */
    public static void showAppColor(Context ctx, String pkg, String label, Runnable after)
    {
        show(ctx, ctx.getString(R.string.dlg_app_color, label), color -> {
            Prefs.setAppColor(ctx, pkg, color);
            if (after != null) { after.run(); }
        });
    }

    public static void show(Context ctx, String title, OnColorPicked callback)
    {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        root.setPadding(pad, dp(ctx, 12), pad, dp(ctx, 8));

        Button auto = new Button(ctx);
        auto.setText(R.string.color_auto);
        auto.setTextSize(17);
        auto.setAllCaps(false);
        auto.setTextColor(ContextCompat.getColor(ctx, R.color.hc_text));
        auto.setBackgroundResource(R.drawable.bg_btn_secondary);
        auto.setMinHeight(dp(ctx, 52));
        auto.setStateListAnimator(null);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.bottomMargin = dp(ctx, 14);
        root.addView(auto, alp);

        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(4);
        root.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(root)
                .setNegativeButton(R.string.no, null)
                .create();

        auto.setOnClickListener(v -> {
            callback.onColorPicked(Color.TRANSPARENT);
            dialog.dismiss();
        });

        int size = dp(ctx, 54);
        int margin = dp(ctx, 8);
        for (int i = 0; i < PRESET_COLORS.length; i++)
        {
            final int color = PRESET_COLORS[i];
            View circle = new View(ctx);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(color);
            gd.setStroke(dp(ctx, 2), ContextCompat.getColor(ctx, R.color.hc_text_dim));
            circle.setBackground(gd);
            circle.setContentDescription(ctx.getString(PRESET_NAMES[i]));
            circle.setClickable(true);
            circle.setFocusable(true);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(margin, margin, margin, margin);
            circle.setLayoutParams(lp);
            circle.setOnClickListener(v -> {
                callback.onColorPicked(color);
                dialog.dismiss();
            });
            grid.addView(circle);
        }

        dialog.show();
    }

    private static int dp(Context ctx, int v)
    {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }
}
