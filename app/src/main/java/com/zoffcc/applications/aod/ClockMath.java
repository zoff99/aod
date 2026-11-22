package com.zoffcc.applications.aod;

/**
 * Pure geometry and mode logic for the clock above the icons. No Android
 * classes here, so the placement rules are host tested like IconMath.
 *
 * The clock is bottom-anchored: each line sits a chosen distance above the
 * top of the icon block, so it follows the icons wherever their anchor and
 * offset put them. If that would push a line off the top of the panel, it
 * is clamped to the margin instead of disappearing.
 *
 * The shared vertical offset marks the top of the whole AOD group, not just
 * the icons: aboveIconsH() is how far the topmost clock line reaches above
 * the icons, and the renderer lowers the icon block by that amount so the
 * clock starts at the offset and the icons hang below it. With the clock
 * off the height is zero and the icons sit at the offset exactly as before.
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

    /**
     * How far the topmost visible clock line reaches above the icon block:
     * the larger of (distance above the icons + text height) over the lines
     * that are shown. Zero when the clock is off, so the icons keep their
     * old position. The renderer adds this to the icon top for the top and
     * camera anchors, making the vertical offset the top of the whole group.
     */
    public static float aboveIconsH(boolean showTime, float timeOffsetPx, float timeTextH,
                                    boolean showDate, float dateOffsetPx, float dateTextH)
    {
        float h = 0f;
        if (showTime) { h = Math.max(h, timeOffsetPx + timeTextH); }
        if (showDate) { h = Math.max(h, dateOffsetPx + dateTextH); }
        return h;
    }

    /**
     * Delay until the clock next needs a redraw, in milliseconds, or -1 when
     * neither line is shown (no periodic wake at all). With the time shown
     * this is the next minute boundary; with only the date shown it is the
     * next local midnight, so a date-only clock wakes once a day instead of
     * every minute. Pure java, so the rule is host tested.
     */
    public static long nextRefreshMs(String mode, long nowMillis)
    {
        if (showTime(mode))
        {
            return 60000L - (nowMillis % 60000L) + 50L;
        }
        if (showDate(mode))
        {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(nowMillis);
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            return Math.max(1000L, cal.getTimeInMillis() - nowMillis);
        }
        return -1L;
    }
}
