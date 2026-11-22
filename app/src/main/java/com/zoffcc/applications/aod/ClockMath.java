package com.zoffcc.applications.aod;

/**
 * Pure geometry and mode logic for the clock above the icons. No Android
 * classes here, so the placement rules are host tested like IconMath.
 *
 * The clock is bottom-anchored: each line sits a chosen distance above the
 * top of the icon block, so it follows the icons wherever their anchor and
 * offset put them. If that would push a line off the top of the panel, it
 * is clamped to the margin instead of disappearing.
 */
public final class ClockMath
{
    private ClockMath() {}

    public static boolean showTime(String mode)
    {
        return Prefs.CLOCK_TIME.equals(mode) || Prefs.CLOCK_TIME_DATE.equals(mode);
    }

    public static boolean showDate(String mode)
    {
        return Prefs.CLOCK_DATE.equals(mode) || Prefs.CLOCK_TIME_DATE.equals(mode);
    }

    /**
     * Horizontal placement of a line of text inside the panel, with the
     * same margin the icon rows keep to the panel edges.
     */
    public static float alignX(float screenW, float margin, float textW, String align)
    {
        float x;
        if ("left".equals(align))
        {
            x = margin;
        }
        else if ("right".equals(align))
        {
            x = screenW - margin - textW;
        }
        else
        {
            x = (screenW - textW) / 2f;
        }
        // a very wide line still starts inside the panel
        return Math.max(margin, Math.min(x, Math.max(margin, screenW - margin - textW)));
    }

    /**
     * Top of a line whose bottom sits {@code offsetPx} above the icon block.
     * Never above {@code minTop} (the panel margin): with the icons anchored
     * near the top edge the clock is pushed down, not clipped away.
     */
    public static float topY(float iconTop, float offsetPx, float textH, float minTop)
    {
        return Math.max(minTop, iconTop - offsetPx - textH);
    }
}
