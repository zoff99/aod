package com.zoffcc.applications.aod;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowManager;

/**
 * The brightness the rest of the phone uses, and what our own windows should
 * ask for.
 *
 * Android picks the display brightness from the topmost visible window that
 * sets {@code LayoutParams.screenBrightness}; a window that leaves it at
 * {@link WindowManager.LayoutParams#BRIGHTNESS_OVERRIDE_NONE} has no say.
 * While the panel dozes, the system falls back to its own fixed doze
 * brightness only when nothing else supplied a value - which is why an
 * always-on screen otherwise ignores the brightness slider completely.
 *
 * So the always-on field asks for the current system brightness, scaled by
 * the user's percentage, and hands control back (no override at all) as soon
 * as the phone is awake again, so the lock screen and every app keep using
 * the system value untouched.
 *
 * Only public settings are read: the float brightness key on Android 12+,
 * SCREEN_BRIGHTNESS (0..255) before that. With adaptive brightness Android
 * does not publish the live auto value to apps, so the setting the slider
 * writes is the closest available number; changes to either key, and to the
 * auto-brightness mode, are observed so the field follows the slider live.
 */
public final class Brightness
{
    private Brightness() {}

    /** Never let the panel go fully dark: the light must stay readable. */
    private static final float FLOOR = 0.01f;

    /**
     * Android 12 keeps the slider value here as 0..1 instead of 0..255. It is
     * a world-readable settings key, but the SDK does not expose a constant
     * for it, so the key is named directly. Reading it needs no permission.
     */
    private static final String KEY_BRIGHTNESS_FLOAT = "screen_brightness_float";

    /** The system brightness setting, 0..1. */
    public static float system(Context c)
    {
        float v = -1f;
        if (Build.VERSION.SDK_INT >= 31)
        {
            try
            {
                v = Settings.System.getFloat(c.getContentResolver(),
                        KEY_BRIGHTNESS_FLOAT, -1f);
            }
            catch (Exception ignored) {}
        }
        if (v < 0f)
        {
            try
            {
                v = Settings.System.getInt(c.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, -1) / 255f;
            }
            catch (Exception ignored) {}
        }
        if (v < 0f) { v = 0.5f; }   // unreadable: a sane middle, not full blast
        return clamp(v);
    }

    /** True when adaptive brightness is on (informational, for the log). */
    public static boolean auto(Context c)
    {
        try
        {
            return Settings.System.getInt(c.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1;
        }
        catch (Exception e) { return false; }
    }

    /**
     * What our always-on windows should request: the system brightness times
     * the configured percentage, or "no opinion" when the user prefers to
     * leave the always-on brightness to the phone.
     */
    public static float requested(Context c)
    {
        if (!Prefs.followBrightness(c))
        {
            return WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        float pct = Math.max(5, Math.min(100, Prefs.brightness(c))) / 100f;
        return clamp(system(c) * pct);
    }

    /**
     * Watch the brightness settings. Returns the observer so the caller can
     * unregister it again; the callback runs on the main thread.
     */
    public static ContentObserver watch(Context c, final Runnable onChange)
    {
        ContentObserver o = new ContentObserver(new Handler(Looper.getMainLooper()))
        {
            @Override
            public void onChange(boolean selfChange) { onChange.run(); }
        };
        ContentResolver r = c.getContentResolver();
        try
        {
            r.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, o);
            r.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, o);
            if (Build.VERSION.SDK_INT >= 31)
            {
                r.registerContentObserver(
                        Settings.System.getUriFor(KEY_BRIGHTNESS_FLOAT), false, o);
            }
        }
        catch (Exception ignored) {}
        return o;
    }

    public static void unwatch(Context c, ContentObserver o)
    {
        if (o == null) { return; }
        try { c.getContentResolver().unregisterContentObserver(o); }
        catch (Exception ignored) {}
    }

    private static float clamp(float v)
    {
        return Math.max(FLOOR, Math.min(1f, v));
    }
}
