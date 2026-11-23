package com.zoffcc.applications.aod;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/**
 * Icons for the always-on screen: either the app's launcher icon or the small
 * symbol it posts to the status bar (Notification.smallIcon, loaded from that
 * app's own resources by resource id - never a bitmap supplied by the app).
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

    /**
     * The status-bar symbol of a notification, drawn at the same size as an
     * app icon. {@code resId} lives in the posting app's resources, so they
     * are opened read-only for that one drawable.
     *
     * These symbols are normally flat alpha masks. With the "color" tint such
     * a mask is filled with {@code color} - the accent the app chose for its
     * notifications, or a color derived from its launcher icon. A symbol that
     * already carries real colors keeps them, exactly like an app icon does.
     *
     * @return null when the app has no such drawable, so the caller can fall
     *         back to the launcher icon.
     */
    public static Bitmap getNotificationIcon(Context c, String pkg, int resId, String tint,
                                         int sizePx, int color)
    {
        if (pkg == null || pkg.isEmpty() || resId == 0) { return null; }
        int work = Math.max(MIN_PX, Math.min(MAX_PX, sizePx));
        String key = pkg + "|n" + resId + "|" + tint + "|" + work + "|" + color;
        Bitmap hit = CACHE.get(key);
        if (hit != null) { return hit; }
        Bitmap made = rasteriseNotification(c, pkg, resId, tint, work, color);
        if (made != null) { CACHE.put(key, made); }
        return made;
    }

    /**
     * The pending-alarm symbol: always the OS alarm-clock glyph, never the
     * alarm app's launcher icon and never an app-supplied drawable (the
     * privacy guard forbids loading app Icons; the alarm app's own
     * status-bar source, AlarmClockInfo.getIcon(), is exactly such a hidden
     * app-supplied drawable). Instead the AOD draws the symbol modern
     * Android uses in its status bar: the Google Material "alarm" glyph
     * (Material Symbols Outlined), bundled verbatim as a vector - the same
     * artwork the OS status bar shows on AOSP-family ROMs.
     */
    public static Bitmap getAlarmIcon(Context c, String tint, int sizePx, int color)
    {
        int work = Math.max(MIN_PX, Math.min(MAX_PX, sizePx));
        String key = "aod.alarm|" + tint + "|" + work + "|" + color;
        Bitmap hit = CACHE.get(key);
        if (hit != null) { return hit; }
        Bitmap made = rasteriseAlarm(c, tint, work, color);
        if (made != null) { CACHE.put(key, made); }
        return made;
    }

    private static Bitmap rasteriseAlarm(Context c, String tint, int work, int color)
    {
        Bitmap raw = null;
        try
        {
            Drawable d = androidx.core.content.ContextCompat.getDrawable(c, R.drawable.aod_ic_alarm);
            if (d == null) { return null; }

            raw = Bitmap.createBitmap(work, work, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(raw);
            d.setBounds(0, 0, work, work);
            d.draw(cv);
            return tintGlyph(raw, tint, color);
        }
        catch (Throwable t)
        {
            if (raw != null) { raw.recycle(); }
            return null;
        }
    }

    /**
     * Shared tint path for flat status-bar glyphs (notification symbols,
     * the alarm clock): "color" fills a monochrome mask with the app's
     * accent, "white"/"black" run through the luminance matrices, artwork
     * with real hues is kept as it is.
     */
    private static Bitmap tintGlyph(Bitmap raw, String tint, int color)
    {
        ColorMatrix m = matrix(tint);
        if (m == null && Prefs.ICON_TINT_COLOR.equals(tint) && isMonochrome(raw))
        {
            m = flat(visible(color));
        }
        if (m == null) { return raw; }
        Bitmap out = Bitmap.createBitmap(raw.getWidth(), raw.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(m));
        new Canvas(out).drawBitmap(raw, 0f, 0f, p);
        raw.recycle();
        return out;
    }

    public static void clear()
    {
        CACHE.evictAll();
    }

    @SuppressWarnings("deprecation")
    private static Bitmap rasteriseNotification(Context c, String pkg, int resId,
                                                String tint, int work, int color)
    {
        Bitmap raw = null;
        try
        {
            android.content.res.Resources res = c.getPackageManager().getResourcesForApplication(pkg);
            Drawable d = res.getDrawable(resId, null);
            if (d == null) { return null; }

            raw = Bitmap.createBitmap(work, work, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(raw);
            d.setBounds(0, 0, work, work);
            d.draw(cv);
            return tintGlyph(raw, tint, color);
        }
        catch (Throwable t)
        {
            if (raw != null) { raw.recycle(); }
            return null;
        }
    }

    /** True when no pixel of the glyph carries a real hue: a plain mask. */
    private static boolean isMonochrome(Bitmap b)
    {
        int w = b.getWidth();
        int h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        float[] hsv = new float[3];
        for (int i = 0; i < px.length; i += 3)   // every third pixel is plenty
        {
            int p = px[i];
            if (Color.alpha(p) < 128) { continue; }
            Color.RGBToHSV(Color.red(p), Color.green(p), Color.blue(p), hsv);
            if (hsv[1] > 0.12f) { return false; }
        }
        return true;
    }

    /** Fill every pixel with one color, keeping the alpha mask untouched. */
    private static ColorMatrix flat(int color)
    {
        float r = Color.red(color);
        float g = Color.green(color);
        float b = Color.blue(color);
        return new ColorMatrix(new float[]{
                0f, 0f, 0f, 0f, r,
                0f, 0f, 0f, 0f, g,
                0f, 0f, 0f, 0f, b,
                0f, 0f, 0f, 1f, 0f});
    }

    /** A brand color can be very dark; on a black panel it would vanish. */
    private static int visible(int color)
    {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[2] < 0.55f) { hsv[2] = 0.55f; }
        return Color.HSVToColor(hsv);
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
