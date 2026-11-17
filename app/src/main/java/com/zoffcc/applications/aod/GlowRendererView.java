package com.zoffcc.applications.aod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;

/**
 * Canvas renderer for the notification light. Draws one of three styles
 * (ring around the camera cutout, glowing screen edge, status-bar dot)
 * with one of four animations (pulse, rotate, flash, steady).
 *
 * Design notes:
 *  - the glow is faked with three layered strokes of increasing width and
 *    decreasing alpha; this stays fully hardware-accelerated (no blur mask).
 *  - the frame loop is time-based (elapsedRealtime), so frame drops never
 *    change the animation speed; "eco" mode caps the rate at ~15 fps.
 *  - a small burn-in offset is applied by the owner via {@link #setShift}.
 *  - no allocations happen inside onDraw.
 */
public class GlowRendererView extends View
{
    /** Safety: black out only this fraction of the display height (top);
     *  the remainder at the bottom is left untouched. */
    private static final float BLACKOUT_HEIGHT_FRACTION = 0.66f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blackPaint = new Paint();
    private final Path edgePath = new Path();
    private final RectF edgeRect = new RectF();
    private final RectF ringRect = new RectF();

    private String style = Prefs.STYLE_RING;
    private String anim = Prefs.ANIM_PULSE;
    private int color = Prefs.DEFAULT_COLOR;
    private boolean eco = false;
    private int speedMs = 1600;
    private float strokePx = 12f;
    private int dotXPercent = 50;

    private Rect camera = new Rect();
    private int screenW = 1080;
    private int screenH = 2340;
    private float shiftX = 0f;
    private float shiftY = 0f;

    private boolean blackout = false;
    private boolean running = false;
    private long startElapsed = 0L;
    private long lastFrame = 0L;
    private SweepGradient sweep;
    private int sweepColor = 0;
    private final android.graphics.Matrix sweepMatrix = new android.graphics.Matrix();

    public GlowRendererView(Context context)
    {
        super(context);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        blackPaint.setStyle(Paint.Style.FILL);
        blackPaint.setColor(Color.BLACK);
    }

    // ---- configuration ----------------------------------------------------

    public void configure(String style, String anim, int color, boolean eco,
                          int speedMs, float strokePx, int dotXPercent)
    {
        this.style = style == null ? Prefs.STYLE_RING : style;
        this.anim = anim == null ? Prefs.ANIM_PULSE : anim;
        this.color = color;
        this.eco = eco;
        this.speedMs = Math.max(300, speedMs);
        this.strokePx = Math.max(2f, strokePx);
        this.dotXPercent = Math.max(0, Math.min(100, dotXPercent));
        this.sweep = null; // rebuild lazily with the new color
        invalidate();
    }

    public void setGeometry(Rect cameraRect, Point screenSize)
    {
        this.camera = new Rect(cameraRect);
        this.screenW = screenSize.x;
        this.screenH = screenSize.y;
        rebuildPaths();
        invalidate();
    }

    /** Burn-in protection: owner cycles a small translation over time. */
    public void setShift(float x, float y)
    {
        this.shiftX = x;
        this.shiftY = y;
        invalidate();
    }

    public void start()
    {
        running = true;
        startElapsed = SystemClock.elapsedRealtime();
        lastFrame = 0L;
        postInvalidateOnAnimation();
    }

    public void stop()
    {
        running = false;
        invalidate();
    }

    public boolean isRunning() { return running; }

    /** Opaque black background (hides the system AOD clock). Drawn even
     *  when the animation is stopped, so an idle screen-off overlay is a
     *  static, near-zero-cost black field. */
    public void setBlackout(boolean on)
    {
        if (blackout != on)
        {
            blackout = on;
            invalidate();
        }
    }

    private void rebuildPaths()
    {
        float inset = strokePx * 1.5f + 2f;
        edgeRect.set(inset, inset, screenW - inset, screenH - inset);
        float corner = Math.min(screenW, screenH) * 0.06f;
        edgePath.reset();
        edgePath.addRoundRect(edgeRect, corner, corner, Path.Direction.CW);
    }

    // ---- frame loop ---------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas)
    {
        if (blackout)
        {
            // Safety: cover only the top two-thirds of the display; the bottom
            // third is left as-is. Opaque black hides the system AOD clock; on
            // OLED these pixels are effectively off, so a static blackout costs
            // almost nothing.
            canvas.drawRect(0f, 0f, screenW, screenH * BLACKOUT_HEIGHT_FRACTION, blackPaint);
        }
        if (!running) { return; }

        long now = SystemClock.elapsedRealtime();
        if (eco && now - lastFrame < 66L)
        {
            postInvalidateOnAnimation();
            return;
        }
        lastFrame = now;

        float t = ((now - startElapsed) % speedMs) / (float) speedMs; // 0..1 phase
        float alpha;
        switch (anim)
        {
            case Prefs.ANIM_FLASH:
                alpha = t < 0.55f ? 1f : 0f;
                break;
            case Prefs.ANIM_STEADY:
                alpha = 1f;
                break;
            case Prefs.ANIM_ROTATE:
                alpha = 1f;
                break;
            case Prefs.ANIM_PULSE:
            default:
                alpha = 0.30f + 0.70f * (0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI - Math.PI / 2));
                break;
        }

        if (alpha > 0.01f)
        {
            canvas.save();
            canvas.translate(shiftX, shiftY);
            if (Prefs.STYLE_EDGE.equals(style))
            {
                drawEdge(canvas, t, alpha);
            }
            else if (Prefs.STYLE_DOT.equals(style))
            {
                drawDot(canvas, alpha);
            }
            else
            {
                drawRing(canvas, t, alpha);
            }
            canvas.restore();
        }

        if (Prefs.ANIM_STEADY.equals(anim))
        {
            // steady needs only slow redraws (burn-in shift updates)
            postDelayedInvalidate();
        }
        else
        {
            postInvalidateOnAnimation();
        }
    }

    private void postDelayedInvalidate()
    {
        postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                if (running) { invalidate(); }
            }
        }, eco ? 250L : 100L);
    }

    // ---- styles -------------------------------------------------------------

    private void drawRing(Canvas canvas, float t, float alpha)
    {
        float pad = strokePx * 1.6f;
        ringRect.set(camera.left - pad, camera.top - pad, camera.right + pad, camera.bottom + pad);
        float radius = Math.min(ringRect.width(), ringRect.height()) / 2f;

        if (Prefs.ANIM_ROTATE.equals(anim))
        {
            ensureSweep();
            canvas.save();
            canvas.rotate(t * 360f, ringRect.centerX(), ringRect.centerY());
            paint.setShader(sweep);
            strokeGlow(canvas, ringRect, radius, alpha);
            paint.setShader(null);
            canvas.restore();
        }
        else
        {
            paint.setShader(null);
            strokeGlow(canvas, ringRect, radius, alpha);
        }
    }

    private void drawEdge(Canvas canvas, float t, float alpha)
    {
        if (Prefs.ANIM_ROTATE.equals(anim))
        {
            ensureSweep();
            canvas.save();
            canvas.rotate(t * 360f, screenW / 2f, screenH / 2f);
            paint.setShader(sweep);
            strokeGlowPath(canvas, alpha);
            paint.setShader(null);
            canvas.restore();
        }
        else
        {
            paint.setShader(null);
            strokeGlowPath(canvas, alpha);
        }
    }

    private void drawDot(Canvas canvas, float alpha)
    {
        float r = strokePx * 1.15f + 3f;
        float cx = screenW * dotXPercent / 100f;
        float cy = Math.max(r + 4f, screenH * 0.014f);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        int base = Color.argb(Math.round(70 * alpha), Color.red(color), Color.green(color), Color.blue(color));
        paint.setColor(base);
        canvas.drawCircle(cx, cy, r * 2.1f, paint);
        paint.setColor(Color.argb(Math.round(140 * alpha), Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawCircle(cx, cy, r * 1.45f, paint);
        paint.setColor(Color.argb(Math.round(255 * alpha), Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawCircle(cx, cy, r, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    /** Three layered strokes = cheap glow, no software blur needed. */
    private void strokeGlow(Canvas canvas, RectF rect, float radius, float alpha)
    {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);

        paint.setStrokeWidth(strokePx * 3.2f);
        if (paint.getShader() == null) { paint.setColor(Color.argb(Math.round(46 * alpha), r, g, b)); }
        else { paint.setAlpha(Math.round(46 * alpha)); }
        canvas.drawRoundRect(rect, radius, radius, paint);

        paint.setStrokeWidth(strokePx * 1.9f);
        if (paint.getShader() == null) { paint.setColor(Color.argb(Math.round(110 * alpha), r, g, b)); }
        else { paint.setAlpha(Math.round(110 * alpha)); }
        canvas.drawRoundRect(rect, radius, radius, paint);

        paint.setStrokeWidth(strokePx);
        if (paint.getShader() == null) { paint.setColor(Color.argb(Math.round(255 * alpha), r, g, b)); }
        else { paint.setAlpha(Math.round(255 * alpha)); }
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setAlpha(255);
    }

    private void strokeGlowPath(Canvas canvas, float alpha)
    {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);

        paint.setStrokeWidth(strokePx * 3.2f);
        if (paint.getShader() == null) { paint.setColor(Color.argb(Math.round(40 * alpha), r, g, b)); }
        else { paint.setAlpha(Math.round(40 * alpha)); }
        canvas.drawPath(edgePath, paint);

        paint.setStrokeWidth(strokePx * 1.9f);
        if (paint.getShader() == null) { paint.setColor(Color.argb(Math.round(100 * alpha), r, g, b)); }
        else { paint.setAlpha(Math.round(100 * alpha)); }
        canvas.drawPath(edgePath, paint);

        paint.setStrokeWidth(strokePx);
        if (paint.getShader() == null) { paint.setColor(Color.argb(Math.round(255 * alpha), r, g, b)); }
        else { paint.setAlpha(Math.round(255 * alpha)); }
        canvas.drawPath(edgePath, paint);
        paint.setAlpha(255);
    }

    private void ensureSweep()
    {
        if (sweep == null || sweepColor != color)
        {
            sweepColor = color;
            int transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color));
            sweep = new SweepGradient(0f, 0f,
                    new int[]{transparent, color, color, transparent},
                    new float[]{0f, 0.35f, 0.65f, 1f});
        }
        // the gradient is centered on the view; translate it to the shape center
        if (Prefs.STYLE_EDGE.equals(style))
        {
            sweepMatrix.setTranslate(screenW / 2f, screenH / 2f);
        }
        else
        {
            sweepMatrix.setTranslate(camera.centerX(), camera.centerY());
        }
        sweep.setLocalMatrix(sweepMatrix);
    }
}
