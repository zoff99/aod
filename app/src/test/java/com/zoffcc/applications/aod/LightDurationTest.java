package com.zoffcc.applications.aod;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Host tests for the stepped light duration: every slider position has to be
 * a useful value, and any stored number of seconds has to land on one of
 * them. ./gradlew :app:testDebugUnitTest
 */
public class LightDurationTest
{
    @Test
    public void rangeIsOneMinuteToThreeHours()
    {
        assertEquals(60, LightDuration.seconds(0));
        assertEquals(3 * 60 * 60, LightDuration.seconds(LightDuration.count() - 1));
    }

    @Test
    public void notTooManySteps()
    {
        // a slider with more stops than this becomes hard to aim at again
        assertTrue("too few steps", LightDuration.count() >= 8);
        assertTrue("too many steps", LightDuration.count() <= 14);
    }

    @Test
    public void stepsRiseStrictly()
    {
        for (int i = 1; i < LightDuration.count(); i++)
        {
            assertTrue("steps must rise", LightDuration.seconds(i) > LightDuration.seconds(i - 1));
        }
    }

    @Test
    public void everyStepIsAWholeNumberOfMinutes()
    {
        for (int i = 0; i < LightDuration.count(); i++)
        {
            assertEquals("step " + i + " is not a whole minute",
                    0, LightDuration.seconds(i) % 60);
        }
    }

    @Test
    public void everyStepSnapsToItself()
    {
        for (int i = 0; i < LightDuration.count(); i++)
        {
            assertEquals(i, LightDuration.nearestIndex(LightDuration.seconds(i)));
        }
    }

    @Test
    public void oldDefaultOf290SecondsBecomesFiveMinutes()
    {
        int index = LightDuration.nearestIndex(290);
        assertEquals(300, LightDuration.seconds(index));
    }

    @Test
    public void absurdStoredValuesClampToTheEnds()
    {
        assertEquals(60, LightDuration.seconds(LightDuration.nearestIndex(0)));
        assertEquals(60, LightDuration.seconds(LightDuration.nearestIndex(-500)));
        assertEquals(10800, LightDuration.seconds(LightDuration.nearestIndex(Integer.MAX_VALUE)));
    }

    @Test
    public void indexIsAlwaysInsideTheTable()
    {
        assertEquals(0, LightDuration.clampIndex(-3));
        assertEquals(LightDuration.count() - 1, LightDuration.clampIndex(999));
        assertEquals(2, LightDuration.clampIndex(2));
    }

    @Test
    public void halfwayBetweenTwoStepsPicksTheLowerOne()
    {
        // 180 s is exactly between 120 and 300: ties must be deterministic
        assertEquals(120, LightDuration.seconds(LightDuration.nearestIndex(180)));
    }

    @Test
    public void snappingNeverMovesFurtherThanHalfTheGap()
    {
        for (int seconds = 1; seconds <= 10800; seconds += 7)
        {
            int index = LightDuration.nearestIndex(seconds);
            int chosen = LightDuration.seconds(index);
            for (int i = 0; i < LightDuration.count(); i++)
            {
                assertTrue("a closer step exists for " + seconds,
                        Math.abs((long) chosen - seconds) <= Math.abs((long) LightDuration.seconds(i) - seconds));
            }
        }
    }
}
