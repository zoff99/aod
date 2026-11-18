package com.zoffcc.applications.aod;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Puts the accessibility service back after an app update.
 *
 * Replacing a package makes the system re-evaluate its accessibility
 * bindings, and on many builds the service ends up switched off. An app may
 * not grant itself accessibility — unless the user handed it
 * WRITE_SECURE_SETTINGS through adb, which is a deliberate, one-time act and
 * survives updates (only an uninstall clears it).
 *
 * This never runs without that permission, never runs while the in-app master
 * switch is off, and never runs on a device where the service has not been
 * enabled before: a first install still asks the user, and somebody who
 * switched the app off is left alone.
 */
public final class AccessRestore
{
    private AccessRestore() {}

    private static final String KEY_SERVICES = "enabled_accessibility_services";
    private static final String KEY_ENABLED = "accessibility_enabled";

    public static boolean canWrite(Context c)
    {
        try
        {
            return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    == PackageManager.PERMISSION_GRANTED;
        }
        catch (Throwable t) { return false; }
    }

    public static boolean isEnabled(Context c)
    {
        try
        {
            String flat = Settings.Secure.getString(c.getContentResolver(), KEY_SERVICES);
            return flat != null && flat.contains(component(c));
        }
        catch (Throwable t) { return false; }
    }

    private static String component(Context c)
    {
        return new ComponentName(c.getPackageName(), overlayservice.class.getName())
                .flattenToString();
    }

    /**
     * @param why short reason for the debug log ("update", "boot", "appStart")
     * @return true when the service was switched back on by this call
     */
    public static boolean restore(Context c, String why)
    {
        if (!Prefs.enabled(c)) { return false; }          // user switched the app off
        if (!Prefs.accessSeen(c)) { return false; }       // never enabled here: ask, don't grab
        if (!canWrite(c)) { return false; }               // no adb grant: nothing we may do
        if (isEnabled(c)) { return false; }

        String cn = component(c);
        try
        {
            String current = Settings.Secure.getString(c.getContentResolver(), KEY_SERVICES);
            if (current == null || "null".equals(current)) { current = ""; }
            String updated;
            if (TextUtils.isEmpty(current)) { updated = cn; }
            else if (current.contains(cn)) { updated = current; }
            else { updated = current + ":" + cn; }        // keep other services intact

            Settings.Secure.putString(c.getContentResolver(), KEY_SERVICES, updated);
            Settings.Secure.putInt(c.getContentResolver(), KEY_ENABLED, 1);
            Dbg.log("restore", "accessibility re-enabled after " + why + " -> " + updated);
            return true;
        }
        catch (Throwable t)
        {
            Dbg.log("restore", "failed after " + why + ": " + t);
            return false;
        }
    }
}
