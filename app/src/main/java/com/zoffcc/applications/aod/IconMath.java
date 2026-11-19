package com.zoffcc.applications.aod;

/**
 * The arithmetic behind the icon block, kept free of Android types so it can
 * be unit tested on the host. Every formula here is the one the renderer
 * used before it was moved out; nothing about the layout changed.
 *
 * Two groups of rules live here:
 *
 *  - the grid: how many icons go into a row, how many rows are needed, how
 *    many of them fit above the bottom limit, and where a row starts;
 *  - the number under an icon: which counter is shown, and how several
 *    counters of the same app are combined.
 */
public final class IconMath
{
    private IconMath() {}

    /**
     * Icons per row. A fixed setting wins; otherwise as many as fit into the
     * available width. Never more than there are icons, never less than one.
     */
    public static int columns(int icons, int perRow, float avail, float iconPx, float gap)
    {
        int cols = perRow > 0 ? perRow : (int) ((avail + gap) / (iconPx + gap));
        return Math.max(1, Math.min(cols, icons));
    }

    /** Rows needed for that many icons at that row width (rounded up). */
    public static int rows(int icons, int cols)
    {
        return (icons + cols - 1) / cols;
    }

    /**
     * How many rows fit between the top of the block and the bottom limit.
     * At least one row is always kept, even if it does not fit.
     */
    public static int rowsThatFit(float top, float limit, float cellH, float rowGap)
    {
        return Math.max(1, (int) ((limit - top + rowGap) / (cellH + rowGap)));
    }

    /**
     * Left edge of one row. Centred rows use their own width, except that a
     * short last row can be aligned to the full row instead. The user offset
     * moves the block, but never off the panel.
     */
    public static float rowStartX(float screenW, float rowW, float fullRowW,
                                  boolean centerH, boolean shortLast, boolean lastRowCenter,
                                  float offset, float margin)
    {
        float base;
        if (centerH)
        {
            float w = shortLast && !lastRowCenter ? fullRowW : rowW;
            base = (screenW - w) / 2f;
        }
        else
        {
            base = margin;
        }
        return Math.max(0f, Math.min(base + offset, Math.max(0f, screenW - rowW)));
    }

    /**
     * Combine the counter of a new notification with what the app already
     * reported: the highest value, or the sum when "Add up" is switched on.
     */
    public static int mergeOwn(int have, int value, boolean add)
    {
        return add ? have + value : Math.max(have, value);
    }

    /**
     * The number drawn under an icon.
     *
     * Source "Notifications": how many notifications the app has posted.
     * Source "The app's own counter": what the app says, raised by its group
     * summary if it published one, and never lower than the number of
     * notifications actually present.
     *
     * A missing counter is passed as 0.
     */
    public static int shownCount(int notifications, int ownCounter, int summaryCounter,
                                 boolean fromApp)
    {
        if (!fromApp) { return notifications; }
        int app = Math.max(ownCounter, summaryCounter);
        return Math.max(app, notifications);
    }
}
