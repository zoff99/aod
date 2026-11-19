package com.zoffcc.applications.aod;

import android.content.Context;

/**
 * The light duration is a choice between fixed, useful steps rather than a
 * free number of seconds: a slider with 290 positions was impossible to aim
 * at, and almost every position was a value nobody wants.
 *
 * Twelve steps from one minute to three hours, roughly logarithmic so the
 * short end stays fine-grained and the long end is still reachable. The
 * slider position is the index into this table, so every position is a
 * snap point and the thumb cannot land between two values.
 */
public final class LightDuration
{
    private LightDuration() {}

    /** 1, 2, 5, 10, 15, 20, 30, 45 min, 1 h, 1.5 h, 2 h, 3 h */
    public static final int[] STEPS_SEC = {
            60, 120, 300, 600, 900, 1200, 1800, 2700, 3600, 5400, 7200, 10800
    };

    public static int count()
    {
        return STEPS_SEC.length;
    }

    public static int clampIndex(int index)
    {
        return Math.max(0, Math.min(STEPS_SEC.length - 1, index));
    }

    public static int seconds(int index)
    {
        return STEPS_SEC[clampIndex(index)];
    }

    /**
     * Closest step to a stored number of seconds. Older installs (and the
     * CI script) can hold any value; the settings screen shows the nearest
     * step instead of an off-grid number.
     */
    public static int nearestIndex(int seconds)
    {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < STEPS_SEC.length; i++)
        {
            long distance = Math.abs((long) STEPS_SEC[i] - (long) seconds);
            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    public static String label(Context c, int index)
    {
        return labelForSeconds(c, seconds(index));
    }

    /** "5 min", "1 h", "1 h 30 min" -- never a raw second count. */
    public static String labelForSeconds(Context c, int seconds)
    {
        int minutes = Math.max(1, Math.round(seconds / 60f));
        if (minutes < 60)
        {
            return c.getString(R.string.dur_min, minutes);
        }
        int hours = minutes / 60;
        int rest = minutes % 60;
        return rest == 0 ? c.getString(R.string.dur_h, hours)
                : c.getString(R.string.dur_h_min, hours, rest);
    }
}
