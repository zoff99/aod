package com.zoffcc.applications.aod;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowManager;

/**
 * Brightness of the always-on field.
 *
 * What Android actually does with brightness (AOSP DisplayPowerController),
 * and why this class is so small:
 *
 *  - A window that asks for a brightness wins over everything else, and
 *    asking switches auto brightness off completely. So any request of ours
 *    disconnects the panel from the brightness slider whenever adaptive
 *    brightness is on: the slider works through the auto-brightness
 *    controller, which a request disables.
 *  - A dozing panel that nobody makes a request for uses the ROM's own
 *    fixed doze brightness. No slider moves that.
 *  - An awake panel that nobody makes a request for uses the system
 *    brightness: adaptive or manual, slider included, live.
 *
 * So being exactly as bright as the rest of the phone is not something to
 * compute - it is something to *not interfere with*: paint the dark field
 * on an awake panel ({@link AodScreenActivity} keeps the screen on) and ask
 * for nothing. That is what "use the system brightness" does, and it is why
 * the always-on field then follows the slider like any other screen.
 *
 * The alternative is an own, fixed brightness. That is requested explicitly
 * and is honoured on an awake panel as well as on a dozing one.
 *
 * Keeping the panel awake is the user's choice ("keep the panel awake"),
 * because it costs battery. With it off the panel dozes, and following the
 * system brightness is not possible at all - so an own brightness is used.
 *
 * No brightness setting is read to make this decision, and none is ever
 * written. The only settings access here is the adaptive-brightness mode,
 * which the settings screen shows as information.
 */
public final class Brightness
{
    private Brightness() {}

    /** Never let the panel go fully dark: the light must stay readable. */
    private static final float FLOOR = 0.01f;

    /**
     * What our always-on windows should request: nothing at all when the
     * phone's own brightness is wanted, otherwise the configured percentage
     * as an absolute brightness.
     */
    public static float requested(Context c)
    {
        if (usesSystem(c))
        {
            return WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        float pct = Math.max(5, Math.min(100, Prefs.brightness(c))) / 100f;
        return Math.max(FLOOR, Math.min(1f, pct));
    }

    /**
     * True when the phone's own brightness drives the always-on field. That
     * needs both an awake panel (a dozing one is pinned to the ROM's fixed
     * level) and no brightness request of ours (a request switches auto
     * brightness off, which is what the slider works through).
     */
    public static boolean usesSystem(Context c)
    {
        return Prefs.awakePanel(c) && Prefs.followBrightness(c);
    }

    /** True when adaptive brightness is on (shown in the settings screen). */
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
     * Watch the adaptive-brightness mode so the settings screen can say
     * which case applies. Returns the observer for unregistering; the
     * callback runs on the main thread.
     */
    public static ContentObserver watch(Context c, final Runnable onChange)
    {
        ContentObserver o = new ContentObserver(new Handler(Looper.getMainLooper()))
        {
            @Override
            public void onChange(boolean selfChange) { onChange.run(); }
        };
        try
        {
            c.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, o);
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
}
