package com.zoffcc.applications.aod;

import android.content.Context;
import android.graphics.Typeface;
import android.util.DisplayMetrics;

/**
 * Everything the renderer needs to draw the clock above the icons, read
 * from the settings once per change -- the same pattern as IconLayout.
 *
 * The clock is only ever drawn while icons are visible: no notification,
 * no clock, the panel stays completely black. Sizes and distances are
 * stored in dp and converted here with the panel density.
 */
public final class ClockLayout
{
    public boolean showTime = false;
    public boolean showDate = false;
    public float timeSizePx = 28f;
    public float dateSizePx = 16f;
    public String timeFont = "normal";
    public String dateFont = "normal";
    public String timeAlign = "center";
    public String dateAlign = "center";
    public float timeOffsetPx = 44f;
    public float dateOffsetPx = 16f;

    public static ClockLayout from(Context c)
    {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float d = dm.density;
        ClockLayout l = new ClockLayout();
        String mode = Prefs.clockMode(c);
        l.showTime = ClockMath.showTime(mode);
        l.showDate = ClockMath.showDate(mode);
        // text follows the font scale like the counter numbers do
        l.timeSizePx = Math.max(8f, Prefs.clockTimeSizeDp(c) * dm.scaledDensity);
        l.dateSizePx = Math.max(8f, Prefs.clockDateSizeDp(c) * dm.scaledDensity);
        l.timeFont = Prefs.clockTimeFont(c);
        l.dateFont = Prefs.clockDateFont(c);
        l.timeAlign = Prefs.clockTimeAlign(c);
        l.dateAlign = Prefs.clockDateAlign(c);
        l.timeOffsetPx = Prefs.clockTimeOffsetDp(c) * d;
        l.dateOffsetPx = Prefs.clockDateOffsetDp(c) * d;
        return l;
    }

    /**
     * The offered fonts are all built into Android -- nothing is downloaded
     * and no library is added. An unknown key falls back to the normal face.
     */
    public static Typeface typeface(String key)
    {
        if ("light".equals(key)) { return Typeface.create("sans-serif-light", Typeface.NORMAL); }
        if ("medium".equals(key)) { return Typeface.create("sans-serif-medium", Typeface.NORMAL); }
        if ("condensed".equals(key)) { return Typeface.create("sans-serif-condensed", Typeface.NORMAL); }
        if ("serif".equals(key)) { return Typeface.SERIF; }
        if ("monospace".equals(key)) { return Typeface.MONOSPACE; }
        return Typeface.DEFAULT;
    }
}
