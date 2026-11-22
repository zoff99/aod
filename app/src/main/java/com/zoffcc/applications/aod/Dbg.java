package com.zoffcc.applications.aod;

import android.util.Log;

/**
 * Central logging constants and the diagnostic logging switch.
 *
 * Two streams, both easy to filter:
 *
 *  - {@link #TAG} ("AODDBG") carries the verbose troubleshooting log written
 *    through {@link #log(String, String)}. Set DEBUG_LOGGING to false to
 *    silence all of it: `adb logcat -s AODDBG` then prints nothing.
 *  - {@link #TAG_PREFIX} + component ("AOD-Overlay", "AOD-Notifications",
 *    "AOD-Main", "AOD-Boot") carries the few lifecycle and error messages
 *    that stay in a release build. They are always on and never contain
 *    notification content.
 *
 * `adb logcat -s AODDBG AOD-Overlay AOD-Notifications AOD-Main AOD-Boot`
 * shows both streams and nothing from other apps.
 */
public final class Dbg
{
    public static final boolean DEBUG_LOGGING = false;

    /** logcat tag of the verbose, switchable troubleshooting stream. */
    public static final String TAG = "AODDBG";

    /** Prefix of the per-component tags; every tag stays under 23 chars,
     *  the length Log.isLoggable() accepts. */
    public static final String TAG_PREFIX = "AOD-";

    private Dbg() {}

    public static void log(String component, String msg)
    {
        if (DEBUG_LOGGING)
        {
            Log.i(TAG, component + ": " + msg);
        }
    }
}
