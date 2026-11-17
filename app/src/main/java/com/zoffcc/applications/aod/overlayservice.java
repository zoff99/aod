package com.zoffcc.applications.aod;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The heart of the app: an AccessibilityService used ONLY as an overlay
 * privilege. Windows of type TYPE_ACCESSIBILITY_OVERLAY do not require the
 * "display over other apps" permission, sit above the keyguard, and on many
 * devices remain visible while the panel shows its always-on display.
 *
 * No screen content is ever read: right after connecting, the service
 * restricts its own event stream to a non-existent package, so it receives
 * effectively zero accessibility events.
 *
 * Screen-off strategy (no root required):
 *  - draw the light into the accessibility overlay;
 *  - hold a timeout-bounded PARTIAL wake lock so animation timers keep
 *    running while the application processor would otherwise suspend;
 *  - probe the hidden DRAW wake lock level once; where the OEM permits it,
 *    the display pipeline keeps compositing during doze;
 *  - where the overlay is not visible during native doze (or the device has
 *    no AOD at all), launch {@link AodScreenActivity}: a pure-black, very dim,
 *    show-when-locked activity that acts as a replacement canvas. On OLED
 *    panels black pixels are effectively off, so this looks like an AOD.
 *  - when the burst ends, return the device to the lock screen with
 *    GLOBAL_ACTION_LOCK_SCREEN so it can doze again.
 */
public class overlayservice extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener
{
    private static final String TAG = "D7:olaysrv";

    private static final int OVERLAY_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED |
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS |
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;

    /** Hidden wake lock level that keeps the display drawing during doze.
     *  Normally reserved for system apps; probed once, used only if granted. */
    private static final int DRAW_WAKE_LOCK_LEVEL = 0x00000080;

    private static final long LOCK_GUARD_MS = 1200L;
    private static final long SHIFT_INTERVAL_MS = 58000L;

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private GlowRendererView view;
    private boolean viewAdded = false;

    private SharedPreferences prefs;
    private android.app.KeyguardManager km;
    private long lastUserPresentAt = 0L;
    private PowerManager.WakeLock partialLock;
    private PowerManager.WakeLock drawLock;
    private PowerManager.WakeLock wakeBridge;
    private boolean drawCapable = false;

    private boolean screenOn = true;
    private boolean canvasShown = false;      // AodScreenActivity currently resumed
    private boolean lightActive = false;
    private boolean burstPending = false;     // consumed on next screen-off
    private boolean testMode = false;
    private long lastLockAt = 0L;
    private int testColor = Prefs.DEFAULT_COLOR;

    private List<NotificationItem> items = new ArrayList<>();
    private String lastBurstKey = "";
    private CountDownTimer timeoutTimer;
    private int shiftIndex = 0;
    private static final float[][] SHIFTS = {{0, 0}, {7, 4}, {-6, 7}, {5, -5}, {-7, -4}};

    // ---- lifecycle -----------------------------------------------------------

    @Override
    public void onCreate()
    {
        super.onCreate();
        Log.i(TAG, "onCreate");
    }

    @Override
    protected void onServiceConnected()
    {
        super.onServiceConnected();
        Log.i(TAG, "onServiceConnected");
        h.postDelayed(this::bootstrap, 600L);
    }

    private void bootstrap()
    {
        try
        {
            prefs = Prefs.get(this);
            prefs.registerOnSharedPreferenceChangeListener(this);
            km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);

            // Privacy + battery: this service exists only for its overlay
            // privilege. Restrict the event stream to a package that cannot
            // exist, so no window content is ever delivered to us.
            try
            {
                AccessibilityServiceInfo info = getServiceInfo();
                if (info != null)
                {
                    info.eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED;
                    info.packageNames = new String[]{"invalid.aod.placeholder"};
                    info.notificationTimeout = 600000L;
                    setServiceInfo(info);
                }
            }
            catch (Exception e) { Log.w(TAG, "setServiceInfo: " + e); }

            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            partialLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aod:light");
            partialLock.setReferenceCounted(false);
            wakeBridge = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "aod:wake");
            wakeBridge.setReferenceCounted(false);
            probeDrawLock(pm);

            buildWindow();
            registerReceivers();
            refreshGeometry();

            screenOn = displayState() == Display.STATE_ON;
            Log.i(TAG, "bootstrap done, screenOn=" + screenOn + " drawCapable=" + drawCapable);
        }
        catch (Exception e)
        {
            Log.e(TAG, "bootstrap failed", e);
        }
    }

    private void probeDrawLock(PowerManager pm)
    {
        try
        {
            drawLock = pm.newWakeLock(DRAW_WAKE_LOCK_LEVEL, "aod:draw");
            drawLock.setReferenceCounted(false);
            drawLock.acquire(100L);
            drawCapable = true;
        }
        catch (Throwable t)
        {
            drawLock = null;
            drawCapable = false;
        }
        Log.i(TAG, "draw wake lock capable: " + drawCapable);
    }

    private void buildWindow()
    {
        if (viewAdded) { return; }
        view = new GlowRendererView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                OVERLAY_FLAGS,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        // Cover the whole physical panel, including the status bar band and
        // the camera cutout. The legacy LAYOUT_IN_SCREEN / NO_LIMITS flags
        // alone are not enough on Android 11+: windows are inset to avoid
        // system bars unless fitInsetsTypes is explicitly cleared, and the
        // cutout mode must allow short-edge extension.
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT >= 30)
        {
            lp.setFitInsetsTypes(0);
            lp.setFitInsetsSides(0);
        }
        view.setVisibility(android.view.View.GONE);
        try
        {
            wm.addView(view, lp);
            viewAdded = true;
        }
        catch (Exception e)
        {
            Log.e(TAG, "addView failed", e);
        }
    }

    private void registerReceivers()
    {
        IntentFilter screen = new IntentFilter();
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        screen.addAction(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_USER_PRESENT);
        ContextCompat.registerReceiver(this, screenReceiver, screen, ContextCompat.RECEIVER_NOT_EXPORTED);

        IntentFilter cmd = new IntentFilter();
        cmd.addAction(notificationobserver.ACTION_ITEMS);
        cmd.addAction(notificationobserver.ACTION_TEST);
        cmd.addAction(notificationobserver.ACTION_HIDE);
        cmd.addAction(notificationobserver.ACTION_REEVAL);
        cmd.addAction(notificationobserver.ACTION_AOD_SHOWN);
        cmd.addAction(notificationobserver.ACTION_AOD_GONE);
        cmd.addAction(notificationobserver.ACTION_WAKE);
        ContextCompat.registerReceiver(this, commandReceiver, cmd, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig)
    {
        super.onConfigurationChanged(newConfig);
        refreshGeometry();
    }

    @Override
    public void onDestroy()
    {
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(commandReceiver); } catch (Exception ignored) {}
        try { if (prefs != null) { prefs.unregisterOnSharedPreferenceChangeListener(this); } }
        catch (Exception ignored) {}
        stopBurst(false);
        if (viewAdded && view != null)
        {
            try { wm.removeViewImmediate(view); } catch (Exception ignored) {}
            viewAdded = false;
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event)
    {
        // intentionally empty: the event stream is disabled in bootstrap()
    }

    @Override
    public void onInterrupt() {}

    // ---- receivers -------------------------------------------------------------

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a))
            {
                screenOn = false;
                h.removeCallbacks(evaluateRunnable);
                h.postDelayed(evaluateRunnable, 450L); // let the system settle into doze
            }
            else if (Intent.ACTION_SCREEN_ON.equals(a))
            {
                screenOn = true;
                if (canvasShown)
                {
                    // our own dark canvas woke the screen; its lifecycle
                    // (AOD_SHOWN / AOD_GONE) manages the burst
                }
                else if (lockedScreenOnLightWanted())
                {
                    // waking to the lock screen is NOT an unlock: keep an
                    // active ring, or start one if it was not showing yet
                    if (!lightActive)
                    {
                        startBurst(Prefs.timeoutSec(overlayservice.this) * 1000L, false);
                    }
                }
                else
                {
                    stopBurst(false);
                }
                // the keyguard state can lag the broadcast by a moment;
                // re-check once the wake transition has settled
                h.removeCallbacks(screenOnRecheck);
                h.postDelayed(screenOnRecheck, 400L);
            }
            else if (Intent.ACTION_USER_PRESENT.equals(a))
            {
                screenOn = true;
                lastUserPresentAt = SystemClock.elapsedRealtime();
                burstPending = false;
                stopBurst(false);
                sendLocal(notificationobserver.ACTION_HIDE);
                if (Prefs.clearOnUnlock(overlayservice.this))
                {
                    sendLocal(notificationobserver.ACTION_CLEAR);
                }
            }
        }
    };

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String a = intent.getAction();
            if (a == null) { return; }
            switch (a)
            {
                case notificationobserver.ACTION_ITEMS:
                {
                    ArrayList<NotificationItem> list =
                            intent.getParcelableArrayListExtra(notificationobserver.EXTRA_ITEMS);
                    items = list != null ? list : new ArrayList<>();
                    onItemsChanged();
                    break;
                }
                case notificationobserver.ACTION_TEST:
                    testMode = true;
                    testColor = intent.getIntExtra("color", Prefs.color(overlayservice.this));
                    startBurst(6000L, false);
                    break;
                case notificationobserver.ACTION_HIDE:
                    stopBurst(false);
                    break;
                case notificationobserver.ACTION_REEVAL:
                    reevaluate();
                    break;
                case notificationobserver.ACTION_AOD_SHOWN:
                    canvasShown = true;
                    if (!lightActive) { reevaluate(); }
                    break;
                case notificationobserver.ACTION_AOD_GONE:
                    canvasShown = false;
                    if (screenOn && lockedScreenOnLightWanted()
                            && SystemClock.elapsedRealtime() - lastUserPresentAt >= 1500L)
                    {
                        if (!lightActive)
                        {
                            startBurst(Prefs.timeoutSec(overlayservice.this) * 1000L, false);
                        }
                    }
                    else
                    {
                        stopBurst(false);
                    }
                    break;
                case notificationobserver.ACTION_WAKE:
                    try { wakeBridge.acquire(2000L); } catch (Exception ignored) {}
                    break;
            }
        }
    };

    private final Runnable evaluateRunnable = new Runnable()
    {
        @Override
        public void run() { onScreenOff(); }
    };

    private final Runnable screenOnRecheck = new Runnable()
    {
        @Override
        public void run()
        {
            if (!screenOn || canvasShown) { return; }
            if (lockedScreenOnLightWanted())
            {
                if (!lightActive)
                {
                    startBurst(Prefs.timeoutSec(overlayservice.this) * 1000L, false);
                }
            }
            else if (lightActive && !testMode)
            {
                stopBurst(false);
            }
        }
    };

    // ---- state machine -------------------------------------------------------------

    private boolean effectiveScreenOff()
    {
        return !screenOn || canvasShown;
    }

    /** Awake on the real lock screen (not our canvas): may the light show? */
    private boolean lockedScreenOnLightWanted()
    {
        return Prefs.enabled(this) && Prefs.screenOnLight(this)
                && !items.isEmpty() && !suppressed()
                && km != null && km.isKeyguardLocked();
    }

    private boolean suppressed()
    {
        Calendar now = Calendar.getInstance();
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        return Prefs.inNightWindow(this, minute);
    }

    private void onItemsChanged()
    {
        if (!Prefs.enabled(this)) { stopBurst(false); return; }

        if (items.isEmpty())
        {
            burstPending = false;
            if (lightActive) { endBurst(); }
            return;
        }

        String newestKey = items.get(0).key;
        boolean fresh = !newestKey.equals(lastBurstKey);

        if (effectiveScreenOff())
        {
            if (Prefs.aodLight(this) && !suppressed() && (fresh || !lightActive))
            {
                startBurst(Prefs.timeoutSec(this) * 1000L, true);
            }
        }
        else if (fresh)
        {
            if (Prefs.screenOnLight(this) && km != null && km.isKeyguardLocked() && !suppressed())
            {
                startBurst(Prefs.timeoutSec(overlayservice.this) * 1000L, false);
            }
            else
            {
                burstPending = true; // show when the screen next turns off
            }
        }
        else if (lightActive)
        {
            // same notification updated: refresh color live
            applyAppearance();
        }
    }

    private void onScreenOff()
    {
        if (!Prefs.enabled(this) || !Prefs.aodLight(this)) { return; }
        // some devices emit a stray SCREEN_OFF during the unlock transition;
        // never relight over an awake device right after the user unlocked
        if (displayState() == Display.STATE_ON
                && SystemClock.elapsedRealtime() - lastUserPresentAt < 1500L)
        {
            return;
        }
        // empty items == everything dismissed: nothing may light up again
        if (items.isEmpty() || suppressed()) { return; }
        // relight_relock: after an unlock hid the light, locking or turning
        // the display off again brings it back for undismissed notifications
        if (lightActive || burstPending || Prefs.relightRelock(this))
        {
            burstPending = false;
            startBurst(Prefs.timeoutSec(this) * 1000L, true);
        }
    }

    private void reevaluate()
    {
        if (!Prefs.enabled(this) || suppressed() || items.isEmpty())
        {
            if (lightActive) { endBurst(); }
            return;
        }
        if (effectiveScreenOff())
        {
            if (Prefs.aodLight(this) && !lightActive)
            {
                startBurst(Prefs.timeoutSec(this) * 1000L, true);
            }
        }
        else if (km != null && km.isKeyguardLocked())
        {
            if (Prefs.screenOnLight(this))
            {
                if (!lightActive)
                {
                    startBurst(Prefs.timeoutSec(overlayservice.this) * 1000L, false);
                }
            }
            else if (lightActive && !testMode)
            {
                stopBurst(false);
            }
        }
    }

    // ---- burst control ----------------------------------------------------------------

    private void startBurst(long durationMs, boolean allowCanvas)
    {
        if (view == null || !viewAdded) { return; }
        // backstop against stale events: never light up over an awake,
        // unlocked device, no matter which path asked for it
        if (!testMode && displayState() == Display.STATE_ON
                && km != null && !km.isKeyguardLocked())
        {
            return;
        }
        // the light geometry is portrait-only; skip in landscape
        try
        {
            if (wm.getDefaultDisplay().getRotation() != android.view.Surface.ROTATION_0) { return; }
        }
        catch (Exception ignored) {}
        cancelTimers();

        int color = testMode ? testColor : items.isEmpty() ? Prefs.color(this) : items.get(0).color;
        if (!testMode && !items.isEmpty()) { lastBurstKey = items.get(0).key; }

        applyAppearance();
        refreshGeometry();
        view.setVisibility(android.view.View.VISIBLE);
        view.start();
        lightActive = true;
        shiftIndex = 0;
        view.setShift(0f, 0f);
        h.postDelayed(shiftRunnable, SHIFT_INTERVAL_MS);

        long lockFor = durationMs + 4000L;
        try { partialLock.acquire(lockFor); } catch (Exception ignored) {}
        if (drawCapable && drawLock != null)
        {
            try { drawLock.acquire(lockFor); } catch (Exception ignored) {}
        }

        if (allowCanvas && !canvasShown && displayState() != Display.STATE_ON)
        {
            launchCanvas();
        }

        timeoutTimer = new CountDownTimer(durationMs, 1000L)
        {
            @Override
            public void onTick(long millisUntilFinished)
            {
                if (Prefs.interval(overlayservice.this))
                {
                    long on = Prefs.intervalOnSec(overlayservice.this) * 1000L;
                    long off = Prefs.intervalOffSec(overlayservice.this) * 1000L;
                    long cycle = on + off;
                    if (cycle > 0)
                    {
                        long phase = (durationMs - millisUntilFinished) % cycle;
                        boolean visible = phase < on;
                        if (visible && !view.isRunning())
                        {
                            view.setVisibility(android.view.View.VISIBLE);
                            view.start();
                        }
                        else if (!visible && view.isRunning())
                        {
                            view.stop();
                            view.setVisibility(android.view.View.GONE);
                        }
                    }
                }
            }

            @Override
            public void onFinish()
            {
                endBurst();
            }
        };
        timeoutTimer.start();
        Log.i(TAG, "burst start: " + durationMs + "ms canvas=" + allowCanvas);
    }

    private void endBurst()
    {
        boolean wasOff = effectiveScreenOff();
        boolean wasTest = testMode;
        stopBurst(false);
        testMode = false;
        if (wasOff && !wasTest)
        {
            sendLocal(notificationobserver.ACTION_HIDE);
            lockScreen();
        }
    }

    private void stopBurst(boolean keepCanvas)
    {
        h.removeCallbacks(screenOnRecheck);
        cancelTimers();
        lightActive = false;
        if (view != null)
        {
            view.stop();
            view.setVisibility(android.view.View.GONE);
        }
        release(partialLock);
        release(drawLock);
        if (!keepCanvas && canvasShown)
        {
            sendLocal(notificationobserver.ACTION_HIDE);
        }
    }

    private void cancelTimers()
    {
        if (timeoutTimer != null) { timeoutTimer.cancel(); timeoutTimer = null; }
        h.removeCallbacks(shiftRunnable);
    }

    private final Runnable shiftRunnable = new Runnable()
    {
        @Override
        public void run()
        {
            if (!lightActive || view == null) { return; }
            shiftIndex = (shiftIndex + 1) % SHIFTS.length;
            float d = getResources().getDisplayMetrics().density;
            view.setShift(SHIFTS[shiftIndex][0] * d, SHIFTS[shiftIndex][1] * d);
            h.postDelayed(this, SHIFT_INTERVAL_MS);
        }
    };

    private void applyAppearance()
    {
        if (view == null) { return; }
        int color = testMode ? testColor : items.isEmpty() ? Prefs.color(this) : items.get(0).color;
        float strokePx = Prefs.strokeDp(this) * getResources().getDisplayMetrics().density;
        view.configure(Prefs.style(this), Prefs.anim(this), color, Prefs.eco(this),
                Prefs.speedMs(this), strokePx, Prefs.dotX(this));
    }

    private void refreshGeometry()
    {
        if (view == null) { return; }
        Rect cam = CutoutLocator.cameraRect(this);
        Point size = CutoutLocator.realSize(this);
        view.setGeometry(cam, size);
    }

    // ---- system helpers ------------------------------------------------------------------

    private int displayState()
    {
        try
        {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            return d != null ? d.getState() : Display.STATE_ON;
        }
        catch (Exception e)
        {
            return Display.STATE_ON;
        }
    }

    private void launchCanvas()
    {
        try
        {
            Intent intent = new Intent(this, AodScreenActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            PendingIntent pi = PendingIntent.getActivity(this, 1, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            pi.send();
            Log.i(TAG, "canvas launch requested");
        }
        catch (Exception e)
        {
            Log.w(TAG, "canvas launch failed: " + e);
        }
    }

    private void lockScreen()
    {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLockAt < LOCK_GUARD_MS) { return; }
        lastLockAt = now;
        try
        {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        }
        catch (Exception e)
        {
            Log.w(TAG, "lockScreen: " + e);
        }
    }

    private void sendLocal(String action)
    {
        Intent i = new Intent(action);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private static void release(PowerManager.WakeLock lock)
    {
        try
        {
            if (lock != null && lock.isHeld()) { lock.release(); }
        }
        catch (Exception ignored) {}
    }

    // ---- live reconfiguration ---------------------------------------------------------------

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
    {
        if (Prefs.ENABLED.equals(key))
        {
            if (!Prefs.enabled(this))
            {
                stopBurst(false);
                sendLocal(notificationobserver.ACTION_HIDE);
                try { disableSelf(); } catch (Exception ignored) {}
            }
            return;
        }
        if (Prefs.NIGHT.equals(key) || Prefs.NIGHT_START.equals(key) || Prefs.NIGHT_END.equals(key))
        {
            sendLocal(notificationobserver.ACTION_REEVAL);
        }
        if (lightActive)
        {
            applyAppearance();
            refreshGeometry();
        }
        if (Prefs.CUTOUT_LEFT.equals(key) || Prefs.CUTOUT_OFF_X.equals(key) ||
            Prefs.CUTOUT_OFF_Y.equals(key) || Prefs.CUTOUT_SCALE.equals(key))
        {
            refreshGeometry();
        }
    }
}
