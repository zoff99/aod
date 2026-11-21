package com.zoffcc.applications.aod;

/**
 * The icon size is a choice between fixed, useful steps rather than a free
 * number of dp: almost every position of a 5..96 slider is a value nobody
 * wants, and aiming at one specific size was hard.
 *
 * Sixteen steps from 5 to 96 dp. The small end stays fine-grained (2 dp
 * apart around the common sizes), the large end widens so 96 dp is still
 * reachable without endless dragging. The shipped default, 18 dp, is one
 * of the steps -- a default is a starting point, not a minimum.
 *
 * The slider position is the index into this table, so every position is
 * a snap point and the thumb cannot land between two values.
 */
public final class IconSize
{
    private IconSize() {}

    /** 5, 8, 10, 12, 14, 16, 18, 20, 24, 28, 32, 40, 48, 64, 80, 96 dp */
    public static final int[] STEPS_DP = {
            5, 8, 10, 12, 14, 16, 18, 20, 24, 28, 32, 40, 48, 64, 80, 96
    };

    public static int count()
    {
        return STEPS_DP.length;
    }

    public static int clampIndex(int index)
    {
        return Math.max(0, Math.min(STEPS_DP.length - 1, index));
    }

    public static int dp(int index)
    {
        return STEPS_DP[clampIndex(index)];
    }

    /**
     * Closest step to a stored size. Older installs hold any value from the
     * former free slider, and an import can carry one too; the settings
     * screen shows the nearest step instead of an off-grid number.
     */
    public static int nearestIndex(int dp)
    {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < STEPS_DP.length; i++)
        {
            long distance = Math.abs((long) STEPS_DP[i] - (long) dp);
            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }
}
