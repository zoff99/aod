package com.zoffcc.applications.aod;

/**
 * What to do with persistent notifications (ongoing, foreground-service or
 * not clearable): music players, downloads, navigation -- and phone calls,
 * which are persistent too and therefore used to disappear.
 *
 * The decision itself is pure and unit tested. Reading the metadata that
 * tells a call apart stays in the notification listener, which is the only
 * class allowed to open a notification at all.
 */
public final class PersistentPolicy
{
    private PersistentPolicy() {}

    /** Show every persistent notification (the old "ignore" switch off). */
    public static final String ALL = "all";
    /** Only calls, nothing else that is persistent. */
    public static final String CALLS = "calls";
    /** Calls plus the apps picked in the always-allow list. Default. */
    public static final String CALLS_APPS = "calls_apps";
    /** Ignore all of them (the old "ignore" switch on). */
    public static final String NONE = "none";

    public static final String[] MODES = {NONE, CALLS, CALLS_APPS, ALL};

    public static int indexOf(String mode)
    {
        for (int i = 0; i < MODES.length; i++)
        {
            if (MODES[i].equals(mode)) { return i; }
        }
        return 2;   // CALLS_APPS
    }

    public static boolean valid(String mode)
    {
        for (String m : MODES) { if (m.equals(mode)) { return true; } }
        return false;
    }

    /**
     * @param mode      one of the four constants above
     * @param isCall    the notification is tagged as a call, takes over the
     *                  screen, or uses the call layout
     * @param appListed the posting app is in the always-allow list
     */
    public static boolean allow(String mode, boolean isCall, boolean appListed)
    {
        if (ALL.equals(mode)) { return true; }
        if (NONE.equals(mode)) { return false; }
        if (isCall) { return true; }
        return CALLS_APPS.equals(mode) && appListed;
    }

    /** Does this mode need the app list at all? Used to show its button. */
    public static boolean usesAppList(String mode)
    {
        return CALLS_APPS.equals(mode);
    }
}
