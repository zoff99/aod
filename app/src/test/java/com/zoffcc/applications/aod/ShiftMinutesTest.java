package com.zoffcc.applications.aod;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Host tests for the stepped burn-in move interval: 1, 2, 10 or 20 minutes,
 * 10 by default. Every slider position has to be one of those, and any
 * stored value has to land on one of them.
 * ./gradlew :app:testDebugUnitTest
 */
public class ShiftMinutesTest
{
    @Test
    public void theFourStepsAreOneTwoTenTwenty()
    {
        assertEquals(4, ShiftMinutes.count());
        assertEquals(1, ShiftMinutes.min(0));
        assertEquals(2, ShiftMinutes.min(1));
        assertEquals(10, ShiftMinutes.min(2));
        assertEquals(20, ShiftMinutes.min(3));
    }

    @Test
    public void stepsRiseStrictly()
    {
        for (int i = 1; i < ShiftMinutes.count(); i++)
        {
            assertTrue("steps must rise", ShiftMinutes.min(i) > ShiftMinutes.min(i - 1));
        }
    }

    @Test
    public void theDefaultIsTenMinutesAndIsASelectableStep()
    {
        int def = SettingsSchema.defInt(Prefs.ICON_SHIFT_MIN);
        assertEquals(10, def);
        assertEquals(def, ShiftMinutes.min(ShiftMinutes.nearestIndex(def)));
        assertEquals(2, ShiftMinutes.nearestIndex(def));
    }

    @Test
    public void everyStepSnapsToItself()
    {
        for (int i = 0; i < ShiftMinutes.count(); i++)
        {
            assertEquals(i, ShiftMinutes.nearestIndex(ShiftMinutes.min(i)));
        }
    }

    @Test
    public void importedOffGridValuesSnapToAStep()
    {
        assertEquals(2, ShiftMinutes.min(ShiftMinutes.nearestIndex(3)));
        assertEquals(10, ShiftMinutes.min(ShiftMinutes.nearestIndex(9)));
        assertEquals(20, ShiftMinutes.min(ShiftMinutes.nearestIndex(16)));
    }

    @Test
    public void absurdStoredValuesClampToTheEnds()
    {
        assertEquals(1, ShiftMinutes.min(ShiftMinutes.nearestIndex(0)));
        assertEquals(1, ShiftMinutes.min(ShiftMinutes.nearestIndex(-500)));
        assertEquals(20, ShiftMinutes.min(ShiftMinutes.nearestIndex(Integer.MAX_VALUE)));
    }

    @Test
    public void indexIsAlwaysInsideTheTable()
    {
        assertEquals(0, ShiftMinutes.clampIndex(-3));
        assertEquals(ShiftMinutes.count() - 1, ShiftMinutes.clampIndex(999));
        assertEquals(2, ShiftMinutes.clampIndex(2));
    }

    @Test
    public void halfwayBetweenTwoStepsPicksTheLowerOne()
    {
        // 6 minutes is exactly between 2 and 10; 15 between 10 and 20
        assertEquals(2, ShiftMinutes.min(ShiftMinutes.nearestIndex(6)));
        assertEquals(10, ShiftMinutes.min(ShiftMinutes.nearestIndex(15)));
    }

    @Test
    public void snappingNeverMovesFurtherThanHalfTheGap()
    {
        for (int min = 1; min <= 40; min++)
        {
            int chosen = ShiftMinutes.min(ShiftMinutes.nearestIndex(min));
            for (int i = 0; i < ShiftMinutes.count(); i++)
            {
                assertTrue("a closer step exists for " + min,
                        Math.abs((long) chosen - min) <= Math.abs((long) ShiftMinutes.min(i) - min));
            }
        }
    }

    @Test
    public void theShortestIntervalIsStillAWholeMinute()
    {
        // anything below a minute would wake the phone as often as the
        // clock does and would no longer be a battery-friendly choice
        assertTrue(ShiftMinutes.min(0) >= 1);
        assertTrue(ShiftMinutes.min(ShiftMinutes.count() - 1) <= 60);
    }
}
