package com.zoffcc.applications.aod;

import android.util.Log;

/**
 * Central diagnostic logging switch.
 *
 * Set DEBUG_LOGGING to false to silence ALL troubleshooting logs added
 * for field debugging (they share the single logcat tag "AODDBG", so
 * `adb logcat -s AODDBG` captures exactly this output and nothing else).
 */
public final class Dbg
{
    public static final boolean DEBUG_LOGGING = true;

    private Dbg() {}

    public static void log(String component, String msg)
    {
        if (DEBUG_LOGGING)
        {
            Log.i("AODDBG", component + ": " + msg);
        }
    }
}
