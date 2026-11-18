package com.zoffcc.applications.aod;

import android.content.Context;
import android.util.DisplayMetrics;

/**
 * Everything the renderer needs to place the app icons on the always-on
 * screen, read from the settings in one go. Density conversion happens here
 * so the renderer only ever deals with pixels.
 *
 * The block is laid out as rows of icons. Where it sits is decided by three
 * things: the horizontal rule (centered or left, plus an offset), the vertical
 * anchor (below the camera, from the top, from the bottom) plus its offset,
 * and how many icons go into one row.
 */
public final class IconLayout
{
    // per-icon appearance
    public int sizePx = 96;
    public boolean plate = false;
    public boolean lightPlate = false;
    public boolean overUi = false;      // test burst: draw without a black field

    // horizontal
    public boolean centerH = true;
    public boolean lastRowCenter = true;
    public int offsetXPx = 0;           // signed, added to the computed start

    // vertical
    public String yAnchor = Prefs.ICON_Y_CAMERA;
    public int offsetYPx = 0;

    // grid
    public int perRow = 0;              // 0 = as many as fit the width
    public int gapPx = 24;
    public int rowGapPx = 24;
    public int maxIcons = 12;
    public boolean clipToBlackout = true;

    // notification count under each icon
    public boolean count = true;
    public boolean countAlways = false;
    public int countTextPx = 30;
    public boolean countBadge = false;

    public static IconLayout from(Context c)
    {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float d = dm.density;
        IconLayout l = new IconLayout();

        l.sizePx = Math.max(24, Math.round(Prefs.iconSizeDp(c) * d));
        l.plate = Prefs.iconPlate(c);
        l.lightPlate = Prefs.ICON_TINT_BLACK.equals(Prefs.iconTint(c));

        l.centerH = Prefs.iconCenterH(c);
        l.lastRowCenter = Prefs.iconLastRowCenter(c);
        l.offsetXPx = Math.round(Prefs.iconOffsetXDp(c) * d);
        l.yAnchor = Prefs.iconYAnchor(c);
        l.offsetYPx = Math.round(Prefs.iconOffsetYDp(c) * d);
        l.perRow = Prefs.iconPerRow(c);
        l.gapPx = Math.round(Prefs.iconGapDp(c) * d);
        l.rowGapPx = Math.round(Prefs.iconRowGapDp(c) * d);
        l.maxIcons = Math.max(1, Prefs.iconMax(c));
        l.clipToBlackout = Prefs.iconClip(c);

        l.count = Prefs.iconCount(c);
        l.countAlways = Prefs.iconCountAlways(c);
        l.countTextPx = Math.max(8, Math.round(Prefs.iconCountSizeDp(c) * dm.scaledDensity));
        l.countBadge = Prefs.iconCountBadge(c);
        return l;
    }

    /** Compact form for the debug log. */
    @Override
    public String toString()
    {
        return "center=" + centerH + " lastRowCenter=" + lastRowCenter
                + " dx=" + offsetXPx + " anchor=" + yAnchor + " dy=" + offsetYPx
                + " perRow=" + perRow + " gap=" + gapPx + " rowGap=" + rowGapPx
                + " max=" + maxIcons + " clip=" + clipToBlackout
                + " count=" + count + " always=" + countAlways
                + " countPx=" + countTextPx + " badge=" + countBadge;
    }
}
