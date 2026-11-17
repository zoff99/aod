package com.zoffcc.applications.aod;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * Central settings store. Every component reads its configuration from here,
 * and the overlay service re-configures itself live on any change.
 */
public final class Prefs
{
    private Prefs() {}

    public static final String FILE = "aod_settings";

    // ---- master / modes -------------------------------------------------
    public static final String ENABLED = "enabled";                 // master switch
    public static final String AOD_LIGHT = "aod_light";             // light while screen off / AOD
    public static final String SCREEN_ON_LIGHT = "screen_on_light"; // light on the lock screen while locked
    public static final String PREVIEW = "preview";                 // notification text on the dark canvas
    public static final String DOUBLE_TAP_WAKE = "double_tap_wake";
    public static final String CLEAR_ON_UNLOCK = "clear_on_unlock"; // dismiss notifications on unlock
    public static final String RELIGHT_RELOCK = "relight_relock";   // light again after unlock + re-lock

    // ---- appearance -----------------------------------------------------
    public static final String STYLE = "style";                     // ring | edge | dot
    public static final String ANIM = "anim";                       // pulse | rotate | flash | steady
    public static final String ECO = "eco";                         // reduced frame rate
    public static final String COLOR = "color";
    public static final String AUTO_COLOR = "auto_color";           // derive color from the app icon
    public static final String BRIGHTNESS = "brightness";           // 5..100 (dark canvas brightness)
    public static final String STROKE_DP = "stroke_dp";             // light thickness
    public static final String DOT_X = "dot_x";                     // 0..100 horizontal dot position

    // ---- timing ---------------------------------------------------------
    public static final String TIMEOUT_SEC = "timeout_sec";         // total light duration per burst
    public static final String INTERVAL = "interval";               // on/off cycling to save power
    public static final String INTERVAL_ON_SEC = "interval_on_sec";
    public static final String INTERVAL_OFF_SEC = "interval_off_sec";
    public static final String SPEED_MS = "speed_ms";               // animation cycle length

    // ---- suppression ----------------------------------------------------
    public static final String NIGHT = "night";
    public static final String NIGHT_START = "night_start";         // minutes since midnight
    public static final String NIGHT_END = "night_end";
    public static final String RESPECT_DND = "respect_dnd";
    public static final String BATTERY_LIGHT = "battery_light";     // charging / full / low battery
    public static final String IGNORE_PERSISTENT = "ignore_persistent";

    // ---- app filter -----------------------------------------------------
    public static final String FILTER_MODE = "filter_mode";         // all | selected
    public static final String FILTER_APPS = "filter_apps";         // set of package names
    public static final String APP_COLORS = "app_colors";           // "pkg=#AARRGGBB;..." overrides

    // ---- cutout calibration ----------------------------------------------
    public static final String CUTOUT_LEFT = "cutout_left";
    public static final String CUTOUT_TOP = "cutout_top";
    public static final String CUTOUT_RIGHT = "cutout_right";
    public static final String CUTOUT_BOTTOM = "cutout_bottom";
    public static final String CUTOUT_RES_W = "cutout_res_w";
    public static final String CUTOUT_RES_H = "cutout_res_h";
    public static final String CUTOUT_OFF_X = "cutout_off_x";       // manual fine tuning, px
    public static final String CUTOUT_OFF_Y = "cutout_off_y";
    public static final String CUTOUT_SCALE = "cutout_scale";       // percent, 100 = auto

    // ---- defaults --------------------------------------------------------
    public static final int DEFAULT_COLOR = 0xFF00E5FF;
    public static final String STYLE_RING = "ring";
    public static final String STYLE_EDGE = "edge";
    public static final String STYLE_DOT = "dot";
    public static final String ANIM_PULSE = "pulse";
    public static final String ANIM_ROTATE = "rotate";
    public static final String ANIM_FLASH = "flash";
    public static final String ANIM_STEADY = "steady";

    public static SharedPreferences get(Context c)
    {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) { return get(c).getBoolean(ENABLED, true); }
    public static boolean aodLight(Context c) { return get(c).getBoolean(AOD_LIGHT, true); }
    public static boolean screenOnLight(Context c) { return get(c).getBoolean(SCREEN_ON_LIGHT, true); }
    public static boolean preview(Context c) { return get(c).getBoolean(PREVIEW, true); }
    public static boolean doubleTapWake(Context c) { return get(c).getBoolean(DOUBLE_TAP_WAKE, true); }
    public static boolean clearOnUnlock(Context c) { return get(c).getBoolean(CLEAR_ON_UNLOCK, false); }
    public static boolean relightRelock(Context c) { return get(c).getBoolean(RELIGHT_RELOCK, true); }

    public static String style(Context c) { return get(c).getString(STYLE, STYLE_RING); }
    public static String anim(Context c) { return get(c).getString(ANIM, ANIM_PULSE); }
    public static boolean eco(Context c) { return get(c).getBoolean(ECO, false); }
    public static int color(Context c) { return get(c).getInt(COLOR, DEFAULT_COLOR); }
    public static boolean autoColor(Context c) { return get(c).getBoolean(AUTO_COLOR, true); }
    public static int brightness(Context c) { return get(c).getInt(BRIGHTNESS, 35); }
    public static int strokeDp(Context c) { return get(c).getInt(STROKE_DP, 5); }
    public static int dotX(Context c) { return get(c).getInt(DOT_X, 50); }

    public static int timeoutSec(Context c) { return get(c).getInt(TIMEOUT_SEC, 60); }
    public static boolean interval(Context c) { return get(c).getBoolean(INTERVAL, false); }
    public static int intervalOnSec(Context c) { return get(c).getInt(INTERVAL_ON_SEC, 4); }
    public static int intervalOffSec(Context c) { return get(c).getInt(INTERVAL_OFF_SEC, 6); }
    public static int speedMs(Context c) { return get(c).getInt(SPEED_MS, 1600); }

    public static boolean night(Context c) { return get(c).getBoolean(NIGHT, false); }
    public static int nightStart(Context c) { return get(c).getInt(NIGHT_START, 23 * 60); }
    public static int nightEnd(Context c) { return get(c).getInt(NIGHT_END, 7 * 60); }
    public static boolean respectDnd(Context c) { return get(c).getBoolean(RESPECT_DND, true); }
    public static boolean batteryLight(Context c) { return get(c).getBoolean(BATTERY_LIGHT, true); }
    public static boolean ignorePersistent(Context c) { return get(c).getBoolean(IGNORE_PERSISTENT, true); }

    public static boolean filterSelected(Context c) { return "selected".equals(get(c).getString(FILTER_MODE, "all")); }

    public static boolean appAllowed(Context c, String pkg)
    {
        if (!filterSelected(c)) { return true; }
        return get(c).getStringSet(FILTER_APPS, new java.util.HashSet<>()).contains(pkg);
    }

    /** Per-app color override, or {@link Color#TRANSPARENT} when none is stored. */
    public static int appColor(Context c, String pkg)
    {
        String all = get(c).getString(APP_COLORS, "");
        for (String entry : all.split(";"))
        {
            int eq = entry.indexOf('=');
            if (eq > 0 && entry.substring(0, eq).equals(pkg))
            {
                try { return Color.parseColor(entry.substring(eq + 1)); }
                catch (Exception ignored) {}
            }
        }
        return Color.TRANSPARENT;
    }

    public static void setAppColor(Context c, String pkg, int color)
    {
        StringBuilder sb = new StringBuilder();
        String all = get(c).getString(APP_COLORS, "");
        for (String entry : all.split(";"))
        {
            int eq = entry.indexOf('=');
            if (eq > 0 && !entry.substring(0, eq).equals(pkg) && !entry.isEmpty())
            {
                sb.append(entry).append(';');
            }
        }
        if (color != Color.TRANSPARENT)
        {
            sb.append(pkg).append('=').append(String.format("#%08X", color)).append(';');
        }
        get(c).edit().putString(APP_COLORS, sb.toString()).apply();
    }

    /** True when "now" falls inside the configured quiet window (handles midnight wrap). */
    public static boolean inNightWindow(Context c, int minuteOfDay)
    {
        if (!night(c)) { return false; }
        int s = nightStart(c);
        int e = nightEnd(c);
        if (s == e) { return false; }
        if (s < e) { return minuteOfDay >= s && minuteOfDay < e; }
        return minuteOfDay >= s || minuteOfDay < e;
    }
}
