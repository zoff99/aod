package com.zoffcc.applications.aod;

/**
 * The settings that take part in export and import, with their type and
 * their sane range. One table, used by the writer and the reader, so a file
 * can never push a value the UI itself would not offer.
 *
 * Pure Java on purpose: the ranges and the key coverage are host tested.
 *
 * Not exported, because they are state rather than settings:
 *   access_seen      whether the accessibility service was ever enabled
 *   seen_pkgs        the picker's memory of packages that once notified
 *   ignore_persistent  the legacy switch, superseded by persistent_mode
 *   sec_open_*       which settings sections are folded
 */
public final class SettingsSchema
{
    private SettingsSchema() {}

    public static final int BOOL = 0;
    public static final int INT = 1;
    public static final int STRING = 2;
    public static final int SET = 3;

    public static final class Field
    {
        public final String key;
        public final int type;
        public final int min;
        public final int max;
        public final String[] allowed;

        Field(String key, int type, int min, int max, String[] allowed)
        {
            this.key = key;
            this.type = type;
            this.min = min;
            this.max = max;
            this.allowed = allowed;
        }
    }

    private static Field b(String key) { return new Field(key, BOOL, 0, 0, null); }

    private static Field i(String key, int min, int max) { return new Field(key, INT, min, max, null); }

    private static Field s(String key, String... allowed) { return new Field(key, STRING, 0, 0, allowed); }

    private static Field set(String key) { return new Field(key, SET, 0, 0, null); }

    public static final Field[] FIELDS = {
            // master and modes
            b(Prefs.ENABLED), b(Prefs.AOD_LIGHT), b(Prefs.SCREEN_ON_LIGHT), b(Prefs.DOUBLE_TAP_WAKE),
            b(Prefs.CLEAR_ON_UNLOCK), b(Prefs.RELIGHT_RELOCK), b(Prefs.CANVAS_FALLBACK),
            b(Prefs.BLACKOUT_FULL),
            // appearance
            s(Prefs.STYLE, Prefs.STYLE_RING, Prefs.STYLE_EDGE, Prefs.STYLE_DOT, Prefs.STYLE_ICONS),
            s(Prefs.ANIM, Prefs.ANIM_PULSE, Prefs.ANIM_ROTATE, Prefs.ANIM_FLASH, Prefs.ANIM_STEADY),
            b(Prefs.ECO), b(Prefs.AUTO_COLOR),
            i(Prefs.COLOR, Integer.MIN_VALUE, Integer.MAX_VALUE),
            i(Prefs.BRIGHTNESS, 5, 100),
            b(Prefs.FOLLOW_BRIGHTNESS), b(Prefs.AWAKE_PANEL),
            i(Prefs.STROKE_DP, 2, 16), i(Prefs.DOT_X, 0, 100),
            // timing
            i(Prefs.TIMEOUT_SEC, 60, 10800),
            b(Prefs.INTERVAL), i(Prefs.INTERVAL_ON_SEC, 1, 120), i(Prefs.INTERVAL_OFF_SEC, 1, 120),
            i(Prefs.SPEED_MS, 300, 4000),
            // suppression
            b(Prefs.NIGHT), i(Prefs.NIGHT_START, 0, 1439), i(Prefs.NIGHT_END, 0, 1439),
            b(Prefs.RESPECT_DND), b(Prefs.BATTERY_LIGHT),
            s(Prefs.PERSISTENT_MODE, PersistentPolicy.NONE, PersistentPolicy.CALLS,
                    PersistentPolicy.CALLS_APPS, PersistentPolicy.ALL),
            set(Prefs.PERSISTENT_APPS),
            // app filter
            s(Prefs.FILTER_MODE, "all", "selected"),
            set(Prefs.FILTER_APPS),
            s(Prefs.PICKER_FILTER, Prefs.PICKER_ALL, Prefs.PICKER_SELECTED, Prefs.PICKER_UNSELECTED),
            s(Prefs.APP_COLORS),
            // icons
            s(Prefs.ICON_SOURCE, Prefs.ICON_SRC_APP, Prefs.ICON_SRC_NOTIFICATION),
            s(Prefs.ICON_TINT, Prefs.ICON_TINT_COLOR, Prefs.ICON_TINT_WHITE, Prefs.ICON_TINT_BLACK),
            i(Prefs.ICON_SIZE_DP, 18, 96), b(Prefs.ICON_PLATE), b(Prefs.ICON_SHIFT),
            // icon layout
            b(Prefs.ICON_CENTER_H), b(Prefs.ICON_LAST_ROW_CENTER),
            i(Prefs.ICON_OFFSET_X_DP, -200, 200),
            s(Prefs.ICON_Y_ANCHOR, Prefs.ICON_Y_CAMERA, Prefs.ICON_Y_TOP, Prefs.ICON_Y_BOTTOM),
            i(Prefs.ICON_OFFSET_Y_DP, 0, 600),
            i(Prefs.ICON_PER_ROW, 0, 8), i(Prefs.ICON_GAP_DP, 0, 64), i(Prefs.ICON_ROW_GAP_DP, 0, 96),
            i(Prefs.ICON_MAX, 1, 24), b(Prefs.ICON_CLIP),
            // counters
            b(Prefs.ICON_COUNT), b(Prefs.ICON_COUNT_ALWAYS), i(Prefs.ICON_COUNT_SIZE_DP, 10, 28),
            b(Prefs.ICON_COUNT_BADGE),
            s(Prefs.ICON_COUNT_SOURCE, Prefs.ICON_COUNT_SRC_NOTIFICATIONS, Prefs.ICON_COUNT_SRC_APP),
            b(Prefs.ICON_COUNT_ADD),
            // camera calibration (device specific, but part of a backup)
            i(Prefs.CUTOUT_LEFT, -1, 20000), i(Prefs.CUTOUT_TOP, -1, 20000),
            i(Prefs.CUTOUT_RIGHT, -1, 20000), i(Prefs.CUTOUT_BOTTOM, -1, 20000),
            i(Prefs.CUTOUT_RES_W, 0, 20000), i(Prefs.CUTOUT_RES_H, 0, 20000),
            i(Prefs.CUTOUT_OFF_X, -100, 100), i(Prefs.CUTOUT_OFF_Y, -100, 100),
            i(Prefs.CUTOUT_SCALE, 50, 200),
    };

    /** Keys that exist in the store but are deliberately not part of a backup. */
    public static final String[] NOT_EXPORTED = {
            Prefs.ACCESS_SEEN, Prefs.SEEN_PKGS, Prefs.IGNORE_PERSISTENT
    };

    public static Field find(String key)
    {
        if (key == null) { return null; }
        for (Field f : FIELDS)
        {
            if (f.key.equals(key)) { return f; }
        }
        return null;
    }

    /** Folded-section state and anything else that is not a setting. */
    public static boolean isStateKey(String key)
    {
        if (key == null) { return false; }
        if (key.startsWith("sec_open_")) { return true; }
        for (String k : NOT_EXPORTED)
        {
            if (k.equals(key)) { return true; }
        }
        return false;
    }

    public static int clamp(Field f, int value)
    {
        if (f.type != INT) { return value; }
        return Math.max(f.min, Math.min(f.max, value));
    }

    /** A string setting with a fixed set of choices rejects anything else. */
    public static boolean accepts(Field f, String value)
    {
        if (value == null) { return false; }
        if (f.type != STRING) { return false; }
        if (f.allowed == null || f.allowed.length == 0) { return true; }
        for (String a : f.allowed)
        {
            if (a.equals(value)) { return true; }
        }
        return false;
    }
}
