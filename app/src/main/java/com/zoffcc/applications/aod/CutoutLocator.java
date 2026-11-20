package com.zoffcc.applications.aod;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.WindowInsets;

import java.util.List;

/**
 * Finds where the front camera cutout sits, so the "ring" style can be drawn
 * around it. Three sources, in order of preference:
 *
 *  1. the real {@link DisplayCutout} bounding rects reported to any of our
 *     activities (stored in preferences together with the resolution they
 *     were measured at),
 *  2. a generic top-center fallback,
 *  3. manual fine tuning (offset / scale sliders) applied on top.
 *
 * Stored rects are rescaled whenever the current resolution differs from the
 * one they were measured at (e.g. after a display-size setting change).
 */
public final class CutoutLocator
{
    private CutoutLocator() {}

    /** Called from activities when their window insets become available. */
    public static void reportInsets(Context c, WindowInsets insets)
    {
        try
        {
            if (insets == null) { return; }
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout == null) { return; }
            List<Rect> rects = cutout.getBoundingRects();
            if (rects == null || rects.isEmpty()) { return; }

            // pick the rect closest to the top edge (the camera, not a chin)
            Rect best = null;
            for (Rect r : rects)
            {
                if (best == null || r.top < best.top ||
                    (r.top == best.top && r.width() * r.height() > best.width() * best.height()))
                {
                    best = r;
                }
            }
            if (best == null || best.isEmpty()) { return; }

            Point size = realSize(c);
            Prefs.get(c).edit()
                 .putInt(Prefs.CUTOUT_LEFT, best.left)
                 .putInt(Prefs.CUTOUT_TOP, best.top)
                 .putInt(Prefs.CUTOUT_RIGHT, best.right)
                 .putInt(Prefs.CUTOUT_BOTTOM, best.bottom)
                 .putInt(Prefs.CUTOUT_RES_W, size.x)
                 .putInt(Prefs.CUTOUT_RES_H, size.y)
                 .apply();
        }
        catch (Throwable ignored) {}
    }

    /** Current camera rect in real display pixels, with manual tuning applied. */
    public static Rect cameraRect(Context c)
    {
        Point size = realSize(c);
        int w = size.x;
        int h = size.y;
        Rect r;

        int left = Prefs.get(c).getInt(Prefs.CUTOUT_LEFT, SettingsSchema.defInt(Prefs.CUTOUT_LEFT));
        int top = Prefs.get(c).getInt(Prefs.CUTOUT_TOP, SettingsSchema.defInt(Prefs.CUTOUT_TOP));
        int right = Prefs.get(c).getInt(Prefs.CUTOUT_RIGHT, SettingsSchema.defInt(Prefs.CUTOUT_RIGHT));
        int bottom = Prefs.get(c).getInt(Prefs.CUTOUT_BOTTOM, SettingsSchema.defInt(Prefs.CUTOUT_BOTTOM));
        int resW = Prefs.get(c).getInt(Prefs.CUTOUT_RES_W, SettingsSchema.defInt(Prefs.CUTOUT_RES_W));
        int resH = Prefs.get(c).getInt(Prefs.CUTOUT_RES_H, SettingsSchema.defInt(Prefs.CUTOUT_RES_H));

        if (left >= 0 && top >= 0 && right > left && bottom > top && resW > 0 && resH > 0)
        {
            float sx = (float) w / resW;
            float sy = (float) h / resH;
            r = new Rect(Math.round(left * sx), Math.round(top * sy),
                         Math.round(right * sx), Math.round(bottom * sy));
        }
        else
        {
            // generic fallback: small square near the top center
            int d = Math.max(24, Math.round(w * 0.065f));
            int cx = w / 2;
            int cy = Math.round(h * 0.018f) + d / 2;
            r = new Rect(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2);
        }

        // manual fine tuning
        int offX = Prefs.get(c).getInt(Prefs.CUTOUT_OFF_X, SettingsSchema.defInt(Prefs.CUTOUT_OFF_X));
        int offY = Prefs.get(c).getInt(Prefs.CUTOUT_OFF_Y, SettingsSchema.defInt(Prefs.CUTOUT_OFF_Y));
        int scalePct = Prefs.get(c).getInt(Prefs.CUTOUT_SCALE, SettingsSchema.defInt(Prefs.CUTOUT_SCALE));
        r.offset(offX, offY);
        if (scalePct != 100 && scalePct > 20 && scalePct < 400)
        {
            int cx = r.centerX();
            int cy = r.centerY();
            int hw = Math.round(r.width() * scalePct / 200f);
            int hh = Math.round(r.height() * scalePct / 200f);
            r.set(cx - hw, cy - hh, cx + hw, cy + hh);
        }
        return r;
    }

    public static Point realSize(Context c)
    {
        Point p = new Point(1080, 2340);
        try
        {
            Display d = ((android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            d.getRealSize(p);
            if (p.x > p.y)
            {
                int t = p.x; p.x = p.y; p.y = t; // always portrait coordinates
            }
        }
        catch (Throwable ignored) {}
        return p;
    }
}
