package com.zoffcc.applications.aod;

/**
 * The settings that take part in export and import, with their type and
 * their sane range. One table, used by the writer and the reader, so a file
 * can never push a value the UI itself would not offer.
 *
 * Pure Java on purpose: defaults, ranges and key coverage are host tested.
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
        /** The value this app version uses when the store has no entry. */
        public final Object def;

        Field(String key, int type, int min, int max, String[] allowed, Object def)
        {
            this.key = key;
            this.type = type;
            this.min = min;
            this.max = max;
            this.allowed = allowed;
            this.def = def;
        }
    }

    private static Field b(String key, boolean def) { return new Field(key, BOOL, 0, 0, null, def); }

    private static Field i(String key, int min, int max, int def)
    { return new Field(key, INT, min, max, null, def); }

    private static Field s(String key, String def, String... allowed)
    { return new Field(key, STRING, 0, 0, allowed, def); }

    private static Field set(String key) { return new Field(key, SET, 0, 0, null, null); }

    public static final Field[] FIELDS = {
            // master and modes
            b(Prefs.ENABLED, true), b(Prefs.AOD_LIGHT, true), b(Prefs.SCREEN_ON_LIGHT, true),
            b(Prefs.DOUBLE_TAP_WAKE, false), b(Prefs.CLEAR_ON_UNLOCK, false),
            b(Prefs.RELIGHT_RELOCK, true), b(Prefs.CANVAS_FALLBACK, false),
            b(Prefs.BLACKOUT_FULL, true),
            // appearance
            s(Prefs.STYLE, Prefs.STYLE_ICONS,
                    Prefs.STYLE_RING, Prefs.STYLE_EDGE, Prefs.STYLE_DOT, Prefs.STYLE_ICONS),
            s(Prefs.ANIM, Prefs.ANIM_PULSE,
                    Prefs.ANIM_PULSE, Prefs.ANIM_ROTATE, Prefs.ANIM_FLASH, Prefs.ANIM_STEADY),
            b(Prefs.ECO, false), b(Prefs.AUTO_COLOR, true),
            i(Prefs.COLOR, Integer.MIN_VALUE, Integer.MAX_VALUE, Prefs.DEFAULT_COLOR),
            i(Prefs.BRIGHTNESS, 5, 100, 30),
            b(Prefs.FOLLOW_BRIGHTNESS, true), b(Prefs.AWAKE_PANEL, false),
            i(Prefs.STROKE_DP, 2, 16, 5), i(Prefs.DOT_X, 0, 100, 50),
            // timing
            i(Prefs.TIMEOUT_SEC, 60, 10800, 3600),
            b(Prefs.INTERVAL, false), i(Prefs.INTERVAL_ON_SEC, 1, 120, 4),
            i(Prefs.INTERVAL_OFF_SEC, 1, 120, 6),
            i(Prefs.SPEED_MS, 300, 4000, 1600),
            // suppression
            b(Prefs.NIGHT, false), i(Prefs.NIGHT_START, 0, 1439, 23 * 60),
            i(Prefs.NIGHT_END, 0, 1439, 7 * 60),
            b(Prefs.RESPECT_DND, true), b(Prefs.BATTERY_LIGHT, false),
            s(Prefs.PERSISTENT_MODE, PersistentPolicy.CALLS_APPS,
                    PersistentPolicy.NONE, PersistentPolicy.CALLS,
                    PersistentPolicy.CALLS_APPS, PersistentPolicy.ALL),
            set(Prefs.PERSISTENT_APPS),
            // app filter
            s(Prefs.FILTER_MODE, "all", "all", "selected"),
            set(Prefs.FILTER_APPS),
            s(Prefs.PICKER_FILTER, Prefs.PICKER_ALL,
                    Prefs.PICKER_ALL, Prefs.PICKER_SELECTED, Prefs.PICKER_UNSELECTED),
            s(Prefs.APP_COLORS, ""),
            // icons
            s(Prefs.ICON_SOURCE, Prefs.ICON_SRC_NOTIFICATION,
                    Prefs.ICON_SRC_APP, Prefs.ICON_SRC_NOTIFICATION),
            s(Prefs.ICON_TINT, Prefs.ICON_TINT_COLOR,
                    Prefs.ICON_TINT_COLOR, Prefs.ICON_TINT_WHITE, Prefs.ICON_TINT_BLACK),
            i(Prefs.ICON_SIZE_DP, 5, 96, 40), b(Prefs.ICON_PLATE, false), b(Prefs.ICON_SHIFT, true),
            i(Prefs.ICON_SHIFT_MIN, 1, 20, 10),
            // icon layout
            b(Prefs.ICON_CENTER_H, true), b(Prefs.ICON_LAST_ROW_CENTER, true),
            i(Prefs.ICON_OFFSET_X_DP, -200, 200, 0),
            s(Prefs.ICON_Y_ANCHOR, Prefs.ICON_Y_CAMERA,
                    Prefs.ICON_Y_CAMERA, Prefs.ICON_Y_TOP, Prefs.ICON_Y_BOTTOM),
            i(Prefs.ICON_OFFSET_Y_DP, 0, 600, 92),
            i(Prefs.ICON_PER_ROW, 0, 8, 0), i(Prefs.ICON_GAP_DP, 0, 64, 12),
            i(Prefs.ICON_ROW_GAP_DP, 0, 96, 12),
            i(Prefs.ICON_MAX, 1, 24, 12), b(Prefs.ICON_CLIP, true),
            // counters
            b(Prefs.ICON_COUNT, true), b(Prefs.ICON_COUNT_ALWAYS, false),
            i(Prefs.ICON_COUNT_SIZE_DP, 10, 28, 13),
            b(Prefs.ICON_COUNT_BADGE, false),
            s(Prefs.ICON_COUNT_SOURCE, Prefs.ICON_COUNT_SRC_APP,
                    Prefs.ICON_COUNT_SRC_NOTIFICATIONS, Prefs.ICON_COUNT_SRC_APP),
            b(Prefs.ICON_COUNT_ADD, false),
            // clock above the icons
            s(Prefs.CLOCK_MODE, Prefs.CLOCK_NONE,
                    Prefs.CLOCK_NONE, Prefs.CLOCK_TIME, Prefs.CLOCK_TIME_DATE, Prefs.CLOCK_DATE),
            i(Prefs.CLOCK_TIME_SIZE_DP, 8, 72, 50),
            s(Prefs.CLOCK_TIME_FONT, "normal",
                    "normal", "light", "medium", "condensed", "serif", "monospace"),
            s(Prefs.CLOCK_TIME_ALIGN, "center", "left", "center", "right"),
            i(Prefs.CLOCK_TIME_OFFSET_DP, 0, 400, 44),
            i(Prefs.CLOCK_DATE_SIZE_DP, 8, 72, 20),
            s(Prefs.CLOCK_DATE_FONT, "normal",
                    "normal", "light", "medium", "condensed", "serif", "monospace"),
            s(Prefs.CLOCK_DATE_ALIGN, "center", "left", "center", "right"),
            i(Prefs.CLOCK_DATE_OFFSET_DP, 0, 400, 16),
            // camera calibration (device specific, but part of a backup)
            i(Prefs.CUTOUT_LEFT, -1, 20000, -1), i(Prefs.CUTOUT_TOP, -1, 20000, -1),
            i(Prefs.CUTOUT_RIGHT, -1, 20000, -1), i(Prefs.CUTOUT_BOTTOM, -1, 20000, -1),
            i(Prefs.CUTOUT_RES_W, 0, 20000, 0), i(Prefs.CUTOUT_RES_H, 0, 20000, 0),
            i(Prefs.CUTOUT_OFF_X, -100, 100, 0), i(Prefs.CUTOUT_OFF_Y, -100, 100, 0),
            i(Prefs.CUTOUT_SCALE, 50, 200, 100),
    };

    /** Keys that exist in the store but are deliberately not part of a backup. */
    public static final String[] NOT_EXPORTED = {
            Prefs.ACCESS_SEEN, Prefs.SEEN_PKGS, Prefs.IGNORE_PERSISTENT
    };

    /**
     * Defaults live here, once. Prefs and the settings screen both read them
     * from this table, so a backup can write the value an untouched setting
     * really has -- which is what makes an import survive a later change of
     * defaults.
     */
    public static boolean defBool(String key)
    {
        Field f = find(key);
        return f != null && f.type == BOOL && Boolean.TRUE.equals(f.def);
    }

    public static int defInt(String key)
    {
        Field f = find(key);
        return f != null && f.type == INT && f.def instanceof Integer ? (Integer) f.def : 0;
    }

    public static String defString(String key)
    {
        Field f = find(key);
        return f != null && f.type == STRING && f.def instanceof String ? (String) f.def : "";
    }

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
