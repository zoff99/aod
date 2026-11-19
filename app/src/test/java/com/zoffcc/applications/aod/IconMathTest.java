package com.zoffcc.applications.aod;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Host tests for the icon grid and the counter rules. These are the two
 * pieces of the always-on screen that are pure arithmetic, so they can be
 * checked without a device: ./gradlew :app:testDebugUnitTest
 */
public class IconMathTest
{
    private static final float EPS = 0.001f;

    // ---- grid -------------------------------------------------------------

    @Test
    public void fixedPerRowWins()
    {
        // plenty of room for 8, but the user asked for 3
        assertEquals(3, IconMath.columns(10, 3, 1000f, 96f, 24f));
    }

    @Test
    public void autoPerRowFitsTheWidth()
    {
        // 1000 + 24 = 1024 divided by 96 + 24 = 120 -> 8 columns
        assertEquals(8, IconMath.columns(20, 0, 1000f, 96f, 24f));
    }

    @Test
    public void neverMoreColumnsThanIcons()
    {
        assertEquals(2, IconMath.columns(2, 0, 1000f, 96f, 24f));
        assertEquals(2, IconMath.columns(2, 6, 1000f, 96f, 24f));
    }

    @Test
    public void atLeastOneColumn()
    {
        // icon wider than the panel, and a fixed setting of zero
        assertEquals(1, IconMath.columns(4, 0, 100f, 400f, 24f));
        assertEquals(1, IconMath.columns(1, 0, 0f, 96f, 0f));
    }

    @Test
    public void rowsRoundUp()
    {
        assertEquals(1, IconMath.rows(1, 4));
        assertEquals(1, IconMath.rows(4, 4));
        assertEquals(2, IconMath.rows(5, 4));
        assertEquals(3, IconMath.rows(9, 4));
    }

    @Test
    public void rowsThatFitClipsTheBlock()
    {
        // 500 px of room, 100 px per cell, 20 px between rows: (500+20)/120 = 4
        assertEquals(4, IconMath.rowsThatFit(0f, 500f, 100f, 20f));
        assertEquals(1, IconMath.rowsThatFit(0f, 50f, 100f, 20f));
    }

    @Test
    public void oneRowIsAlwaysKept()
    {
        // the block starts below the limit: still one row, never zero
        assertEquals(1, IconMath.rowsThatFit(900f, 500f, 100f, 20f));
    }

    // ---- horizontal placement --------------------------------------------

    @Test
    public void centredRow()
    {
        // (1080 - 300) / 2 = 390
        assertEquals(390f, IconMath.rowStartX(1080f, 300f, 600f,
                true, false, true, 0f, 48f), EPS);
    }

    @Test
    public void shortLastRowCanBeCentredOnItsOwn()
    {
        assertEquals(390f, IconMath.rowStartX(1080f, 300f, 600f,
                true, true, true, 0f, 48f), EPS);
    }

    @Test
    public void shortLastRowCanAlignToTheFullRow()
    {
        // (1080 - 600) / 2 = 240, the left edge of a full row
        assertEquals(240f, IconMath.rowStartX(1080f, 300f, 600f,
                true, true, false, 0f, 48f), EPS);
    }

    @Test
    public void leftAlignedUsesTheMargin()
    {
        assertEquals(48f, IconMath.rowStartX(1080f, 300f, 600f,
                false, false, true, 0f, 48f), EPS);
    }

    @Test
    public void offsetNeverPushesTheRowOffThePanel()
    {
        // far right: clamped to screenW - rowW
        assertEquals(780f, IconMath.rowStartX(1080f, 300f, 600f,
                true, false, true, 5000f, 48f), EPS);
        // far left: clamped to 0
        assertEquals(0f, IconMath.rowStartX(1080f, 300f, 600f,
                true, false, true, -5000f, 48f), EPS);
    }

    @Test
    public void rowWiderThanThePanelStartsAtZero()
    {
        assertEquals(0f, IconMath.rowStartX(400f, 900f, 900f,
                true, false, true, 100f, 48f), EPS);
    }

    // ---- counters ---------------------------------------------------------

    @Test
    public void addUpSumsTheCounters()
    {
        assertEquals(7, IconMath.mergeOwn(4, 3, true));
    }

    @Test
    public void highestIsTheDefault()
    {
        assertEquals(4, IconMath.mergeOwn(4, 3, false));
        assertEquals(9, IconMath.mergeOwn(4, 9, false));
    }

    @Test
    public void notificationSourceIgnoresTheAppCounter()
    {
        assertEquals(3, IconMath.shownCount(3, 42, 99, false));
    }

    @Test
    public void appSourceUsesTheAppCounter()
    {
        assertEquals(42, IconMath.shownCount(3, 42, 0, true));
    }

    @Test
    public void appCounterNeverDropsBelowTheNotificationsPresent()
    {
        assertEquals(5, IconMath.shownCount(5, 2, 0, true));
    }

    @Test
    public void groupSummaryCanRaiseTheAppCounter()
    {
        assertEquals(99, IconMath.shownCount(3, 42, 99, true));
        // but a stale, lower summary cannot lower it
        assertEquals(42, IconMath.shownCount(3, 42, 1, true));
    }

    @Test
    public void missingCountersCountAsZero()
    {
        assertEquals(1, IconMath.shownCount(1, 0, 0, true));
        assertEquals(0, IconMath.shownCount(0, 0, 0, true));
    }

    // ---- logging tags -----------------------------------------------------

    @Test
    public void logTagsStayWithinTheAndroidLimit()
    {
        // Log.isLoggable() rejects tags longer than 23 characters
        assertEquals("AODDBG", Dbg.TAG);
        assertEquals("AOD-", Dbg.TAG_PREFIX);
        String[] components = {"Overlay", "Notifications", "Main", "Boot"};
        for (String c : components)
        {
            String tag = Dbg.TAG_PREFIX + c;
            assertEquals("tag too long: " + tag, true, tag.length() <= 23);
        }
    }
}
