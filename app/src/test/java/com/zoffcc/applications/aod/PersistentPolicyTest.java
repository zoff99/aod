package com.zoffcc.applications.aod;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Host tests for the persistent-notification policy: calls must survive the
 * "ignore persistent" setting, everything else follows the chosen mode.
 * ./gradlew :app:testDebugUnitTest
 */
public class PersistentPolicyTest
{
    @Test
    public void ignoreAllDropsEvenCalls()
    {
        assertFalse(PersistentPolicy.allow(PersistentPolicy.NONE, true, true));
        assertFalse(PersistentPolicy.allow(PersistentPolicy.NONE, false, true));
    }

    @Test
    public void allowAllKeepsEverything()
    {
        assertTrue(PersistentPolicy.allow(PersistentPolicy.ALL, false, false));
        assertTrue(PersistentPolicy.allow(PersistentPolicy.ALL, true, false));
    }

    @Test
    public void callsOnlyKeepsCallsAndNothingElse()
    {
        assertTrue(PersistentPolicy.allow(PersistentPolicy.CALLS, true, false));
        assertFalse(PersistentPolicy.allow(PersistentPolicy.CALLS, false, true));
    }

    @Test
    public void callsPlusAppsKeepsCallsAndListedApps()
    {
        assertTrue(PersistentPolicy.allow(PersistentPolicy.CALLS_APPS, true, false));
        assertTrue(PersistentPolicy.allow(PersistentPolicy.CALLS_APPS, false, true));
        assertFalse(PersistentPolicy.allow(PersistentPolicy.CALLS_APPS, false, false));
    }

    @Test
    public void anOngoingDialerCallNeedsTheList()
    {
        // the AOSP dialer tags only its incoming call: the ongoing one arrives
        // with no call metadata at all, so the seeded dialer entry carries it
        assertFalse("without the dialer in the list the ongoing call is lost",
                PersistentPolicy.allow(PersistentPolicy.CALLS, false, false));
        assertTrue(PersistentPolicy.allow(PersistentPolicy.CALLS_APPS, false, true));
    }

    @Test
    public void onlyOneModeUsesTheAppList()
    {
        assertTrue(PersistentPolicy.usesAppList(PersistentPolicy.CALLS_APPS));
        assertFalse(PersistentPolicy.usesAppList(PersistentPolicy.CALLS));
        assertFalse(PersistentPolicy.usesAppList(PersistentPolicy.NONE));
        assertFalse(PersistentPolicy.usesAppList(PersistentPolicy.ALL));
    }

    @Test
    public void everyModeHasASpinnerPosition()
    {
        for (int i = 0; i < PersistentPolicy.MODES.length; i++)
        {
            assertEquals(i, PersistentPolicy.indexOf(PersistentPolicy.MODES[i]));
        }
        assertEquals(4, PersistentPolicy.MODES.length);
    }

    @Test
    public void unknownModeFallsBackToTheDefault()
    {
        assertFalse(PersistentPolicy.valid("nonsense"));
        assertFalse(PersistentPolicy.valid(null));
        assertEquals(PersistentPolicy.indexOf(PersistentPolicy.CALLS_APPS),
                PersistentPolicy.indexOf("nonsense"));
    }

    @Test
    public void defaultIsCallsPlusApps()
    {
        assertTrue(PersistentPolicy.valid(PersistentPolicy.CALLS_APPS));
        assertEquals(2, PersistentPolicy.indexOf(PersistentPolicy.CALLS_APPS));
        // the default must let a call through without any user configuration
        assertTrue(PersistentPolicy.allow(PersistentPolicy.MODES[2], true, false));
    }

    @Test
    public void nonPersistentNotificationsAreNotAffected()
    {
        // the policy is only consulted for persistent notifications; a normal
        // message never reaches it, which is what these modes must not change
        for (String mode : PersistentPolicy.MODES)
        {
            assertTrue("a listed app is never worse off than before",
                    PersistentPolicy.allow(mode, true, true) || PersistentPolicy.NONE.equals(mode));
        }
    }
}
