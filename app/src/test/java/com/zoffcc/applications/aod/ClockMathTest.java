package com.zoffcc.applications.aod;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Host tests for the clock placement rules: which lines a mode shows, where
 * a line sits horizontally, and how it is anchored above the icons without
 * ever leaving the panel. ./gradlew :app:testDebugUnitTest
 */
public class ClockMathTest
{
    @Test
    public void nothingShowsNothing()
    {
        assertFalse(ClockMath.showTime(Prefs.CLOCK_NONE));
        assertFalse(ClockMath.showDate(Prefs.CLOCK_NONE));
    }

    @Test
    public void timeShowsOnlyTheTime()
    {
        assertTrue(ClockMath.showTime(Prefs.CLOCK_TIME));
        assertFalse(ClockMath.showDate(Prefs.CLOCK_TIME));
    }

    @Test
    public void dateShowsOnlyTheDate()
    {
        assertFalse(ClockMath.showTime(Prefs.CLOCK_DATE));
        assertTrue(ClockMath.showDate(Prefs.CLOCK_DATE));
    }

    @Test
    public void timeDateShowsBoth()
    {
        assertTrue(ClockMath.showTime(Prefs.CLOCK_TIME_DATE));
        assertTrue(ClockMath.showDate(Prefs.CLOCK_TIME_DATE));
    }

    @Test
    public void anUnknownModeShowsNothing()
    {
        assertFalse(ClockMath.showTime("nonsense"));
        assertFalse(ClockMath.showDate("nonsense"));
        assertFalse(ClockMath.showTime(null));
        assertFalse(ClockMath.showDate(null));
    }

    @Test
    public void alignmentLeftCenterRight()
    {
        // panel 1080 wide, margin 48, text 200 wide
        assertEquals(48f, ClockMath.alignX(1080f, 48f, 200f, "left"), 0.01f);
        assertEquals(440f, ClockMath.alignX(1080f, 48f, 200f, "center"), 0.01f);
        assertEquals(832f, ClockMath.alignX(1080f, 48f, 200f, "right"), 0.01f);
    }

    @Test
    public void anUnknownAlignmentCenters()
    {
        assertEquals(440f, ClockMath.alignX(1080f, 48f, 200f, "nonsense"), 0.01f);
    }

    @Test
    public void aVeryWideLineStillStartsInsideThePanel()
    {
        // text wider than the panel minus margins: no negative start
        assertTrue(ClockMath.alignX(1080f, 48f, 2000f, "right") >= 48f);
        assertTrue(ClockMath.alignX(1080f, 48f, 2000f, "center") >= 48f);
    }

    @Test
    public void theLineSitsItsOffsetAboveTheIcons()
    {
        // icons start at 1000, offset 100, text 60 tall -> top at 840
        assertEquals(840f, ClockMath.topY(1000f, 100f, 60f, 48f), 0.01f);
        // offset 0 puts the bottom of the text right on top of the icons
        assertEquals(940f, ClockMath.topY(1000f, 0f, 60f, 48f), 0.01f);
    }

    @Test
    public void iconsNearTheTopPushTheClockDownInsteadOfClippingIt()
    {
        // icons at 80, offset 100, text 60 -> would be -80; clamped to margin
        assertEquals(48f, ClockMath.topY(80f, 100f, 60f, 48f), 0.01f);
    }
}
