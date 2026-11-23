package com.zoffcc.applications.aod;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The lock-screen-like proximity window of the pending-alarm item:
 * notificationobserver.alarmNear() is pure arithmetic, so it is tested on
 * the host. The shipped window is two hours (ALARM_SHOW_WINDOW_MS); the
 * function takes the window as a parameter, so the boundary behaviour is
 * pinned here at a readable size.
 */
public class AlarmNearTest
{
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void alarmOneHourAheadIsNear()
    {
        assertTrue(notificationobserver.alarmNear(NOW + HOUR, NOW, 2 * HOUR));
    }

    @Test
    public void alarmJustUnderTheWindowIsNear()
    {
        assertTrue(notificationobserver.alarmNear(NOW + 2 * HOUR - MIN, NOW, 2 * HOUR));
    }

    @Test
    public void alarmExactlyTheWindowAwayIsNotNear()
    {
        // "less than two hours" - the boundary itself is still hidden
        assertFalse(notificationobserver.alarmNear(NOW + 2 * HOUR, NOW, 2 * HOUR));
    }

    @Test
    public void alarmFarAheadIsNotNear()
    {
        assertFalse(notificationobserver.alarmNear(NOW + 8 * HOUR, NOW, 2 * HOUR));
    }

    @Test
    public void alarmRingingNowIsNear()
    {
        assertTrue(notificationobserver.alarmNear(NOW, NOW, 2 * HOUR));
    }

    @Test
    public void alarmInThePastIsNear()
    {
        // trigger already passed: the alarm is firing/snoozing, keep showing
        assertTrue(notificationobserver.alarmNear(NOW - 10 * MIN, NOW, 2 * HOUR));
    }

    @Test
    public void noAlarmIsNeverNear()
    {
        assertFalse(notificationobserver.alarmNear(0L, NOW, 2 * HOUR));
    }

    @Test
    public void shippedWindowIsTwoHours()
    {
        assertTrue(notificationobserver.ALARM_SHOW_WINDOW_MS == 2 * HOUR);
    }

    @Test
    public void negativeTriggerMeansNoAlarm()
    {
        assertFalse(notificationobserver.alarmNear(-1L, NOW, 2 * HOUR));
    }
}
