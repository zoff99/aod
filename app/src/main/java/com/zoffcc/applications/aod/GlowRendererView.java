package com.zoffcc.applications.aod;

import android.content.Context;
import android.graphics.Bitmap;
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

import java.util.ArrayList;
import java.util.List;

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
    /** Reduced extent, used when "black out the whole display" is switched
     *  off: only this fraction of the display height (top) is covered and
     *  the bottom third is left untouched. The service always applies the
     *  setting through {@link #setBlackoutFull(boolean)}. */
    private static final float BLACKOUT_PARTIAL_FRACTION = 0.66f;
    private float blackoutFraction = 1f;
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

    // No notification text is drawn here, or anywhere else in the app: the
    // renderer only ever sees app icons, numbers and -- when the user asks
    // for it -- the current time and date. See PRIVACY.md.

    // App icons: ready-made bitmaps, laid out once, blitted without any
    // animation. This is the "no LED, no blinking" style.
    private List<Bitmap> icons = null;
    private int[] iconCounts = null;
    private IconLayout iconCfg = new IconLayout();
    private int iconPx = 96;
    private int iconCols = 1;
    private int iconRows = 1;
    private int iconCount = 0;
    private float iconGap = 16f;
    private float iconRowGap = 16f;
    private float iconBadgeH = 0f;
    private float iconTop = 0f;
    private float iconsBottom = 0f;
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint countPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float countAscent = 0f;
    private float countDescent = 0f;
    private final RectF iconRect = new RectF();
    private final RectF badgeRect = new RectF();

    // Clock above the icons: pre-formatted strings, drawn only while icons
    // are visible, refreshed once per minute. An idle black panel gets no
    // timer and no redraws at all.
    private ClockLayout clockCfg = new ClockLayout();
    private String timeText = null;
    private String dateText = null;
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float timeAscent = 0f;
    private float timeDescent = 0f;
    private float dateAscent = 0f;
    private float dateDescent = 0f;

    public GlowRendererView(Context context)
    {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        blackPaint.setStyle(Paint.Style.FILL);
        blackPaint.setColor(Color.BLACK);
        countPaint.setColor(0xFFF2F2F2);
        countPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        countPaint.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);
        Paint.FontMetrics fm = countPaint.getFontMetrics();
        countAscent = -fm.ascent;
        countDescent = fm.descent;
        timePaint.setColor(0xFFF2F2F2);
        datePaint.setColor(0xFFF2F2F2);
    }

    // ---- configuration ----------------------------------------------------

    public void configure(String style, String anim, int color, boolean eco,
                          int speedMs, float strokePx, int dotXPercent)
    {
        this.style = style == null ? Prefs.STYLE_RING : style;
        this.anim = anim == null ? Prefs.ANIM_PULSE : anim;
        this.color = color;
        this.eco = eco;
        // A still image is drawn once and then sits unchanged in the window
        // surface: a GPU layer would cost memory and per-frame upkeep for
        // nothing. Only the styles that really animate get one.
        int layer = Prefs.STYLE_ICONS.equals(this.style) ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE;
        if (getLayerType() != layer) { setLayerType(layer, null); }
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
        layoutIcons();
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
        refreshClock();
        postInvalidateOnAnimation();
    }

    public void stop()
    {
        running = false;
        removeCallbacks(frameTick);
        refreshClock();
        invalidate();
    }

    public boolean isRunning() { return running; }

    /** Opaque black background (hides the system AOD clock). Drawn even
     *  when the animation is stopped, so an idle screen-off overlay is a
     *  static, near-zero-cost black field. */
    /** Extend the black field over the whole panel instead of the top part.
     *  Touches still pass through to the system either way: this only
     *  changes what is visible. */
    public void setBlackoutFull(boolean full)
    {
        float f = full ? 1f : BLACKOUT_PARTIAL_FRACTION;
        if (blackoutFraction != f)
        {
            blackoutFraction = f;
            layoutIcons();
            invalidate();
        }
    }

    public void setBlackout(boolean on)
    {
        if (blackout != on)
        {
            blackout = on;
            invalidate();
        }
    }

    /**
     * Icons of the apps that currently have a notification, newest first,
     * together with how many notifications each of those apps has. Pass null
     * to clear them. The bitmaps are already tinted by the owner; this view
     * only decides where they go, following {@link IconLayout}.
     *
     * @param list   one bitmap per app, newest app first
     * @param counts notification count per app, same order as {@code list}
     * @param cfg    size, position, spacing and count settings
     */
    public void setIcons(List<Bitmap> list, int[] counts, IconLayout cfg)
    {
        this.icons = (list == null || list.isEmpty()) ? null : new ArrayList<>(list);
        this.iconCounts = counts;
        this.iconCfg = cfg == null ? new IconLayout() : cfg;
        this.iconPx = Math.max(24, iconCfg.sizePx);
        countPaint.setTextSize(Math.max(8f, iconCfg.countTextPx));
        Paint.FontMetrics fm = countPaint.getFontMetrics();
        countAscent = -fm.ascent;
        countDescent = fm.descent;
        layoutIcons();
        refreshClock();
        invalidate();
    }

    /** Nothing to show: an idle screen must stay completely black. */
    public void clearIcons()
    {
        icons = null;
        iconCounts = null;
        iconCount = 0;
        iconsBottom = 0f;
        refreshClock();
        invalidate();
    }

    /**
     * Clock configuration (mode, sizes, fonts, alignment, distances). The
     * texts are refreshed and the minute tick re-armed only while icons are
     * actually visible; otherwise this changes nothing on screen.
     */
    public void setClock(ClockLayout cfg)
    {
        this.clockCfg = cfg == null ? new ClockLayout() : cfg;
        timePaint.setTypeface(ClockLayout.typeface(clockCfg.timeFont));
        timePaint.setTextSize(Math.max(8f, clockCfg.timeSizePx));
        datePaint.setTypeface(ClockLayout.typeface(clockCfg.dateFont));
        datePaint.setTextSize(Math.max(8f, clockCfg.dateSizePx));
        Paint.FontMetrics fm = timePaint.getFontMetrics();
        timeAscent = -fm.ascent;
        timeDescent = fm.descent;
        fm = datePaint.getFontMetrics();
        dateAscent = -fm.ascent;
        dateDescent = fm.descent;
        // the clock height changes where the icon block sits, so re-layout
        layoutIcons();
        refreshClock();
    }

    /**
     * Format the current time/date and redraw, but only while something is
     * on screen. The owner (the overlay service) decides WHEN this runs --
     * every minute for the time, once at midnight for a date-only clock --
     * through its alarm path, so the CPU is actually awake to push the frame
     * to a dozing panel. This method never schedules anything itself.
     */
    public void refreshClock()
    {
        if (!running || icons == null || (!clockCfg.showTime && !clockCfg.showDate)) { return; }
        formatClockTexts();
        invalidate();
    }

    /** Runs on the minute tick, never inside onDraw: formatting allocates.
     *  The time follows the phone's 12/24-hour setting; the date is the
     *  locale's weekday-day-month order, without a year. */
    private void formatClockTexts()
    {
        android.content.Context c = getContext();
        java.util.Date now = new java.util.Date();
        if (clockCfg.showTime)
        {
            timeText = android.text.format.DateFormat.getTimeFormat(c).format(now);
        }
        if (clockCfg.showDate)
        {
            String pattern = android.text.format.DateFormat.getBestDateTimePattern(
                    java.util.Locale.getDefault(), "EEE d MMM");
            dateText = new java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(now);
        }
    }

    /** Time and date above the icon block, drifting with it against burn-in. */
    private void drawClock(Canvas canvas)
    {
        if (iconCount <= 0) { return; }
        float density = getResources().getDisplayMetrics().density;
        float margin = 16f * density;
        if (clockCfg.showTime && timeText != null)
        {
            float tw = timePaint.measureText(timeText);
            float x = ClockMath.alignX(screenW, margin, tw, clockCfg.timeAlign) + shiftX;
            float top = ClockMath.topY(iconTop + shiftY, clockCfg.timeOffsetPx,
                    timeAscent + timeDescent, margin);
            canvas.drawText(timeText, x, top + timeAscent, timePaint);
        }
        if (clockCfg.showDate && dateText != null)
        {
            float tw = datePaint.measureText(dateText);
            float x = ClockMath.alignX(screenW, margin, tw, clockCfg.dateAlign) + shiftX;
            float top = ClockMath.topY(iconTop + shiftY, clockCfg.dateOffsetPx,
                    dateAscent + dateDescent, margin);
            canvas.drawText(dateText, x, top + dateAscent, datePaint);
        }
    }

    /**
     * Rows of icons, placed by the configured horizontal rule and vertical
     * anchor. Rows that would not fit are dropped rather than drawn over the
     * part of the screen that must stay untouched (unless the user turned
     * that guard off).
     */
    private void layoutIcons()
    {
        iconsBottom = 0f;
        iconCount = 0;
        if (icons == null) { return; }
        float density = getResources().getDisplayMetrics().density;
        float margin = 16f * density;

        iconGap = Math.max(0f, iconCfg.gapPx);
        iconRowGap = Math.max(0f, iconCfg.rowGapPx);
        // room for the number under the icon, so rows never overlap it
        iconBadgeH = iconCfg.count
                ? (countAscent + countDescent + 6f * density + (iconCfg.countBadge ? 6f * density : 0f))
                : 0f;
        float cellH = iconPx + iconBadgeH;

        float avail = screenW - 2f * margin;
        int cols = IconMath.columns(icons.size(), iconCfg.perRow, avail, iconPx, iconGap);
        int rows = IconMath.rows(icons.size(), cols);

        float blockH = rows * cellH + (rows - 1) * iconRowGap;
        // the shared vertical offset marks the top of the whole group: when
        // the clock is on, lower the icons so the top clock line lands there
        float clockAboveH = ClockMath.aboveIconsH(
                clockCfg.showTime, clockCfg.timeOffsetPx, timeAscent + timeDescent,
                clockCfg.showDate, clockCfg.dateOffsetPx, dateAscent + dateDescent);
        float top = iconTopFor(blockH, density, clockAboveH);
        float limit = (iconCfg.clipToBlackout ? screenH * blackoutFraction : screenH) - margin;
        int fit = IconMath.rowsThatFit(top, limit, cellH, iconRowGap);
        if (rows > fit)
        {
            rows = fit;
            blockH = rows * cellH + (rows - 1) * iconRowGap;
            top = iconTopFor(blockH, density, clockAboveH);
        }

        iconCols = cols;
        iconRows = rows;
        iconCount = Math.min(icons.size(), rows * cols);
        iconTop = top;
        iconsBottom = top + blockH;
    }

    /** Vertical start of the icon block. For the top and camera anchors the
     *  clock height is added, so the offset marks the top of the whole group
     *  (clock first, icons below); the bottom anchor measures to the last row. */
    private float iconTopFor(float blockH, float density, float clockAboveH)
    {
        float top;
        if (Prefs.ICON_Y_TOP.equals(iconCfg.yAnchor))
        {
            top = iconCfg.offsetYPx + clockAboveH;
        }
        else if (Prefs.ICON_Y_BOTTOM.equals(iconCfg.yAnchor))
        {
            top = screenH - iconCfg.offsetYPx - blockH;
        }
        else
        {
            top = Math.max(camera.bottom + 22f * density, screenH * 0.10f) + iconCfg.offsetYPx + clockAboveH;
        }
        // never let the block leave the panel, whatever offset was dialled in
        return Math.max(0f, Math.min(top, Math.max(0f, screenH - blockH)));
    }

    private void drawIcons(Canvas canvas)
    {
        float density = getResources().getDisplayMetrics().density;
        float corner = iconPx * 0.22f;
        float pad = iconCfg.plate || iconCfg.lightPlate ? iconPx * 0.10f : 0f;
        if (pad > 0f)
        {
            platePaint.setColor(iconCfg.lightPlate ? 0xFFE8E8E8 : 0xFF1C1C1E);
        }
        float fullRowW = iconCols * iconPx + (iconCols - 1) * iconGap;
        for (int i = 0; i < iconCount; i++)
        {
            Bitmap b = icons.get(i);
            if (b == null) { continue; }
            int row = i / iconCols;
            int col = i % iconCols;
            int inRow = Math.min(iconCols, iconCount - row * iconCols);
            float rowW = inRow * iconPx + (inRow - 1) * iconGap;

            boolean shortLast = row == iconRows - 1 && inRow < iconCols;
            // centred or left, then the offset, clamped so nothing leaves the panel
            float base = IconMath.rowStartX(screenW, rowW, fullRowW, iconCfg.centerH,
                    shortLast, iconCfg.lastRowCenter, iconCfg.offsetXPx, 16f * density);

            float x = base + col * (iconPx + iconGap) + shiftX;
            float y = iconTop + row * (iconPx + iconBadgeH + iconRowGap) + shiftY;
            if (pad > 0f)
            {
                iconRect.set(x - pad, y - pad, x + iconPx + pad, y + iconPx + pad);
                canvas.drawRoundRect(iconRect, corner + pad, corner + pad, platePaint);
            }
            iconRect.set(x, y, x + iconPx, y + iconPx);
            canvas.drawBitmap(b, null, iconRect, iconPaint);

            if (iconCfg.count && iconCounts != null && i < iconCounts.length)
            {
                int n = iconCounts[i];
                if (n > 1 || (n == 1 && iconCfg.countAlways))
                {
                    drawCount(canvas, n, x + iconPx / 2f, y + iconPx, density);
                }
            }
        }
    }

    /** The number of notifications of one app, centered under its icon. */
    private void drawCount(Canvas canvas, int n, float cx, float iconBottom, float density)
    {
        String s = n > 99 ? "99+" : Integer.toString(n);
        float tw = countPaint.measureText(s);
        float top = iconBottom + 3f * density;
        if (iconCfg.countBadge)
        {
            float padH = 7f * density;
            float padV = 3f * density;
            float h = countAscent + countDescent + 2f * padV;
            float w = Math.max(tw + 2f * padH, h);
            badgeRect.set(cx - w / 2f, top, cx + w / 2f, top + h);
            badgePaint.setColor(0xFF26262A);
            canvas.drawRoundRect(badgeRect, h / 2f, h / 2f, badgePaint);
            top += padV;
        }
        canvas.drawText(s, cx - tw / 2f, top + countAscent, countPaint);
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
            // Cover the whole panel by default, or only the top two thirds
            // when the user asks for it. Opaque black hides the system AOD clock
            // and widgets; on OLED these pixels are effectively off, so a
            // static blackout costs almost nothing.
            canvas.drawRect(0f, 0f, screenW, screenH * blackoutFraction, blackPaint);
            if (running && icons != null) { drawIcons(canvas); drawClock(canvas); }
        }
        else if (running && icons != null && iconCfg.overUi)
        {
            // "Test light" while the app is on screen: show the icons as they
            // would look, without painting a black field over the UI
            drawIcons(canvas);
            drawClock(canvas);
        }
        if (!running) { return; }
        // App icons are a still image: no pulsing, no flashing, no frame loop.
        // The only redraws are the slow burn-in shifts driven by the owner.
        if (Prefs.STYLE_ICONS.equals(style)) { return; }

        long now = SystemClock.elapsedRealtime();
        if (eco && now - lastFrame < 66L)
        {
            // Sleep until the next allowed frame instead of asking for every
            // vsync and throwing the frames away.
            removeCallbacks(frameTick);
            postDelayed(frameTick, 66L - (now - lastFrame));
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

    /** One reusable tick: a new Runnable per frame is needless garbage. */
    private final Runnable frameTick = new Runnable()
    {
        @Override
        public void run()
        {
            if (running) { invalidate(); }
        }
    };

    private void postDelayedInvalidate()
    {
        removeCallbacks(frameTick);
        postDelayed(frameTick, eco ? 250L : 100L);
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
