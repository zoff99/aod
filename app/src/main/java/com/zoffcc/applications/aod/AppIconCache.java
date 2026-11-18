package com.zoffcc.applications.aod;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/**
 * App icons for the always-on screen.
 *
 * Each icon is rasterised once per (package, tint) into a small square
 * bitmap and kept in a tiny LRU cache. The renderer then only blits
 * ready-made bitmaps, so showing icons costs nothing per frame and needs
 * no animation loop at all.
 *
 * Tinting happens with a colour matrix during rasterisation, never with a
 * per-pixel loop:
 *  - "color" keeps the original artwork;
 *  - "white" reduces the icon to its luminance and lifts it, so it reads as
 *    a light glyph on the black panel;
 *  - "black" is the inverted form of the same, for use on a light plate.
 * Alpha is always preserved, so transparent icon backgrounds stay
 * transparent and only the glyph itself is drawn.
 */
public final class AppIconCache
{
    /** Rasterising bounds: never below 96 px (small icons still look clean
     *  when the size is raised later), never above 256 px so a full cache
     *  stays at a few megabytes even on very dense panels. */
    private static final int MIN_PX = 96;
    private static final int MAX_PX = 256;
    private static final int MAX_ENTRIES = 16;

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(MAX_ENTRIES);

    private AppIconCache() {}

    public static Bitmap get(Context c, String pkg, String tint, int sizePx)
    {
        if (pkg == null || pkg.isEmpty()) { return null; }
        int work = Math.max(MIN_PX, Math.min(MAX_PX, sizePx));
        String key = pkg + "|" + tint + "|" + work;
        Bitmap hit = CACHE.get(key);
        if (hit != null) { return hit; }
        Bitmap made = rasterise(c, pkg, tint, work);
        if (made != null) { CACHE.put(key, made); }
        return made;
    }

    public static void clear()
    {
        CACHE.evictAll();
    }

    private static Bitmap rasterise(Context c, String pkg, String tint, int work)
    {
        Bitmap raw = null;
        try
        {
            PackageManager pm = c.getPackageManager();
            Drawable d = pm.getApplicationIcon(pkg);
            if (d == null) { return null; }

            raw = Bitmap.createBitmap(work, work, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(raw);
            d.setBounds(0, 0, work, work);
            d.draw(cv);

            ColorMatrix m = matrix(tint);
            if (m == null) { return raw; }

            Bitmap out = Bitmap.createBitmap(work, work, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setColorFilter(new ColorMatrixColorFilter(m));
            new Canvas(out).drawBitmap(raw, 0f, 0f, p);
            raw.recycle();
            return out;
        }
        catch (Throwable t)
        {
            if (raw != null) { raw.recycle(); }
            return null;
        }
    }

    private static ColorMatrix matrix(String tint)
    {
        if (Prefs.ICON_TINT_WHITE.equals(tint)) { return luminance(0.85f, 40f); }
        if (Prefs.ICON_TINT_BLACK.equals(tint)) { return luminance(-0.85f, 215f); }
        return null;
    }

    /** Luminance -> grey, scaled by k and shifted by offset. A negative k
     *  inverts the result, which is what the black variant needs. */
    private static ColorMatrix luminance(float k, float offset)
    {
        float r = 0.213f * k;
        float g = 0.715f * k;
        float b = 0.072f * k;
        return new ColorMatrix(new float[]{
                r, g, b, 0f, offset,
                r, g, b, 0f, offset,
                r, g, b, 0f, offset,
                0f, 0f, 0f, 1f, 0f});
    }
}
