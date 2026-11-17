package com.zoffcc.applications.aod;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * Small, dependency-free dominant-color extractor used for the "auto color"
 * feature: the notification light takes the most prominent vivid color of
 * the notifying app's icon.
 *
 * Algorithm: downscale the icon, bucket every pixel by hue (12 buckets),
 * weight each pixel by saturation * value, ignore near-gray / near-black /
 * near-white pixels, then return the average color of the strongest bucket.
 */
public final class ColorUtil
{
    private ColorUtil() {}

    private static final int MAX_PIXELS = 96 * 96;
    private static final int HUE_BUCKETS = 12;

    public static int dominantColor(Drawable icon, int fallback)
    {
        try
        {
            Bitmap bmp = toBitmap(icon);
            if (bmp == null) { return fallback; }

            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if (w <= 0 || h <= 0) { return fallback; }

            // downscale so we never scan more than MAX_PIXELS
            double scale = Math.sqrt((double) MAX_PIXELS / (double) (w * h));
            if (scale < 1.0)
            {
                w = Math.max(1, (int) Math.ceil(w * scale));
                h = Math.max(1, (int) Math.ceil(h * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, w, h, true);
                if (scaled != bmp) { bmp.recycle(); }
                bmp = scaled;
            }

            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            bmp.recycle();

            float[] weight = new float[HUE_BUCKETS];
            float[] sumR = new float[HUE_BUCKETS];
            float[] sumG = new float[HUE_BUCKETS];
            float[] sumB = new float[HUE_BUCKETS];
            float[] hsv = new float[3];

            for (int p : px)
            {
                int a = Color.alpha(p);
                if (a < 128) { continue; }
                int r = Color.red(p);
                int g = Color.green(p);
                int b = Color.blue(p);
                Color.RGBToHSV(r, g, b, hsv);
                float sat = hsv[1];
                float val = hsv[2];
                if (sat < 0.25f || val < 0.20f || val > 0.97f) { continue; }
                int bucket = Math.min(HUE_BUCKETS - 1, (int) (hsv[0] / 360f * HUE_BUCKETS));
                float wgt = sat * val;
                weight[bucket] += wgt;
                sumR[bucket] += r * wgt;
                sumG[bucket] += g * wgt;
                sumB[bucket] += b * wgt;
            }

            int best = -1;
            float bestW = 0f;
            for (int i = 0; i < HUE_BUCKETS; i++)
            {
                if (weight[i] > bestW) { bestW = weight[i]; best = i; }
            }
            if (best < 0 || bestW <= 0f) { return fallback; }

            int r = Math.round(sumR[best] / bestW);
            int g = Math.round(sumG[best] / bestW);
            int b = Math.round(sumB[best] / bestW);
            // boost towards a vivid LED-like variant
            Color.RGBToHSV(r, g, b, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.25f + 0.10f);
            hsv[2] = Math.min(1f, hsv[2] * 1.15f + 0.10f);
            return Color.HSVToColor(hsv);
        }
        catch (Throwable t)
        {
            return fallback;
        }
    }

    private static Bitmap toBitmap(Drawable d)
    {
        if (d == null) { return null; }
        if (d instanceof BitmapDrawable)
        {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            return (b != null && !b.isRecycled()) ? b.copy(Bitmap.Config.ARGB_8888, false) : null;
        }
        int w = Math.max(1, d.getIntrinsicWidth());
        int h = Math.max(1, d.getIntrinsicHeight());
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }
}
