package com.zoffcc.applications.aod;

/**
 * The burn-in move interval is a choice between four fixed steps rather than
 * a free number of minutes: 1, 2, 10 or 20. The shipped default, 10 minutes,
 * is one of the steps -- a default is a starting point, not a minimum.
 *
 * Shorter intervals spread the pixel load more evenly but wake the phone a
 * little more often; longer ones save a few wake-ups. The slider position is
 * the index into this table, so every position is a snap point and the thumb
 * cannot land between two values.
 */
public final class ShiftMinutes
{
    private ShiftMinutes() {}

    /** 1, 2, 10, 20 minutes */
    public static final int[] STEPS_MIN = {1, 2, 10, 20};

    public static int count()
    {
        return STEPS_MIN.length;
    }

    public static int clampIndex(int index)
    {
        return Math.max(0, Math.min(STEPS_MIN.length - 1, index));
    }

    public static int min(int index)
    {
        return STEPS_MIN[clampIndex(index)];
    }

    /**
     * Closest step to a stored interval. An import can carry an off-grid
     * value; the settings screen shows the nearest step instead. Ties settle
     * on the lower step, exactly like the icon size.
     */
    public static int nearestIndex(int minutes)
    {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < STEPS_MIN.length; i++)
        {
            long distance = Math.abs((long) STEPS_MIN[i] - (long) minutes);
            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }
}
