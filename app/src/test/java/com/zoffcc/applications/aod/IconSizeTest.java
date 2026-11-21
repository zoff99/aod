package com.zoffcc.applications.aod;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Host tests for the stepped icon size: every slider position has to be a
 * useful size, the default has to be selectable, and any stored size has to
 * land on one of the steps. ./gradlew :app:testDebugUnitTest
 */
public class IconSizeTest
{
    @Test
    public void rangeIsFiveToNinetySixDp()
    {
        assertEquals(5, IconSize.dp(0));
        assertEquals(96, IconSize.dp(IconSize.count() - 1));
    }

    @Test
    public void notTooManySteps()
    {
        // a slider with more stops than this becomes hard to aim at again
        assertTrue("too few steps", IconSize.count() >= 8);
        assertTrue("too many steps", IconSize.count() <= 20);
    }

    @Test
    public void stepsRiseStrictly()
    {
        for (int i = 1; i < IconSize.count(); i++)
        {
            assertTrue("steps must rise", IconSize.dp(i) > IconSize.dp(i - 1));
        }
    }

    @Test
    public void theDefaultIsASelectableStep()
    {
        // 18 dp is the shipped default; a default that cannot be selected
        // again after moving the slider would be a trap
        int def = SettingsSchema.defInt(Prefs.ICON_SIZE_DP);
        assertEquals(18, def);
        assertEquals(def, IconSize.dp(IconSize.nearestIndex(def)));
    }

    @Test
    public void everyStepSnapsToItself()
    {
        for (int i = 0; i < IconSize.count(); i++)
        {
            assertEquals(i, IconSize.nearestIndex(IconSize.dp(i)));
        }
    }

    @Test
    public void oldFreeSliderValuesSnapToAStep()
    {
        // the former slider stored every whole dp from 18 to 96
        assertEquals(24, IconSize.dp(IconSize.nearestIndex(23)));
        assertEquals(40, IconSize.dp(IconSize.nearestIndex(41)));
        assertEquals(96, IconSize.dp(IconSize.nearestIndex(90)));
    }

    @Test
    public void absurdStoredValuesClampToTheEnds()
    {
        assertEquals(5, IconSize.dp(IconSize.nearestIndex(0)));
        assertEquals(5, IconSize.dp(IconSize.nearestIndex(-500)));
        assertEquals(96, IconSize.dp(IconSize.nearestIndex(Integer.MAX_VALUE)));
    }

    @Test
    public void indexIsAlwaysInsideTheTable()
    {
        assertEquals(0, IconSize.clampIndex(-3));
        assertEquals(IconSize.count() - 1, IconSize.clampIndex(999));
        assertEquals(2, IconSize.clampIndex(2));
    }

    @Test
    public void halfwayBetweenTwoStepsPicksTheLowerOne()
    {
        // 30 dp is exactly between 28 and 32: ties must be deterministic
        assertEquals(28, IconSize.dp(IconSize.nearestIndex(30)));
        // 19 dp is exactly between 18 and 20
        assertEquals(18, IconSize.dp(IconSize.nearestIndex(19)));
    }

    @Test
    public void snappingNeverMovesFurtherThanHalfTheGap()
    {
        for (int dp = 1; dp <= 120; dp++)
        {
            int index = IconSize.nearestIndex(dp);
            int chosen = IconSize.dp(index);
            for (int i = 0; i < IconSize.count(); i++)
            {
                assertTrue("a closer step exists for " + dp,
                        Math.abs((long) chosen - dp) <= Math.abs((long) IconSize.dp(i) - dp));
            }
        }
    }
}
