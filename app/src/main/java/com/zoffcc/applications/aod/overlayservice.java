package com.zoffcc.applications.aod;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlarmManager;
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
 *  - the black background and the optional notification text are drawn by
 *    the overlay itself, so nothing has to wake the panel: pressing power
 *    still turns the display off and it stays off;
 *  - only if the user explicitly enables the fallback canvas (phones with no
 *    always-on display of their own) is {@link AodScreenActivity} launched:
 *    a pure-black, very dim, show-when-locked activity. It turns the panel
 *    on, which is exactly why it is opt-in and off by default.
 *  - when the burst ends, return the device to the lock screen with
 *    GLOBAL_ACTION_LOCK_SCREEN so it can doze again.
 */
public class overlayservice extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener
{
    private static final String TAG = Dbg.TAG_PREFIX + "Overlay";

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
    // The burn-in move interval is user-chosen (1/2/10/20 min, default 10);
    // see shiftIntervalMs(). The position also changes at the start of every
    // burst, which is what actually protects the panel, because bursts are
    // usually far shorter than this interval.

    /** Alarm actions for the end of a burst and the burn-in move. An alarm
     *  wakes the phone once at the right moment; a Handler message would
     *  need the CPU held awake for the whole burst just to fire on time. */
    private static final String ACTION_ALARM_END = "com.zoffcc.applications.aod.ALARM_END";
    private static final String ACTION_ALARM_DRIFT = "com.zoffcc.applications.aod.ALARM_DRIFT";
    private static final String ACTION_ALARM_CLOCK = "com.zoffcc.applications.aod.ALARM_CLOCK";
    private static final int REQ_ALARM_END = 4101;
    private static final int REQ_ALARM_DRIFT = 4102;
    private static final int REQ_ALARM_CLOCK = 4103;

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private GlowRendererView view;
    private boolean viewAdded = false;

    private SharedPreferences prefs;
    private android.app.KeyguardManager km;
    private long lastUserPresentAt = 0L;
    private boolean lastKmLocked = true;
    private int kmUnlockedSeen = 0;
    private long relockWatchStart = 0L;
    private long canvasLaunchAt = 0L;
    private long canvasBackoffUntil = 0L;
    private PowerManager.WakeLock partialLock;
    private PowerManager.WakeLock briefLock;
    private boolean cpuLockHeld = false;
    private boolean awakeHeld = false;      // our window holds the display awake
    private boolean awakeTried = false;     // one wake attempt per burst
    private long awakeAttemptAt = 0L;
    private long awakeBackoffUntil = 0L;
    private int awakeLateOffs = 0;
    private boolean timersViaAlarm = false;
    private long burstEndsAt = 0L;
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
    private static final float[][] SHIFTS = {{0, 0}, {2, 1}, {-1, 2}, {-2, -1}, {1, -2}};

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
        Dbg.log("overlay", "onServiceConnected");
        Prefs.markAccessSeen(this);
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
            briefLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aod:tick");
            briefLock.setReferenceCounted(false);
            wakeBridge = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "aod:wake");
            wakeBridge.setReferenceCounted(false);
            probeDrawLock(pm);

            buildWindow();
            registerReceivers();
            refreshGeometry();

            screenOn = displayState() == Display.STATE_ON;
            Log.i(TAG, "bootstrap done, screenOn=" + screenOn + " drawCapable=" + drawCapable);
            Dbg.log("overlay", "bootstrap done drawCapable=" + drawCapable + " " + state());
            syncOverlay();
            // The observer may have published before this receiver existed
            // (fresh install / update). Ask it to re-read the active
            // notifications and publish again so none are missed.
            sendLocal(notificationobserver.ACTION_REEVAL);
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
        // Never request a brightness of our own: a dozing panel pins its own
        // fixed always-on level that requests cannot reliably change (some
        // ROMs clamp them, a few ignore them completely), and any request
        // would take an awake panel off the system slider. The items are
        // faded while drawing instead ("always-on visibility"), and the panel
        // brightness stays entirely the phone's business.
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
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
        screen.addAction(Intent.ACTION_TIME_CHANGED);
        screen.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        screen.addAction(Intent.ACTION_LOCALE_CHANGED);
        ContextCompat.registerReceiver(this, screenReceiver, screen, ContextCompat.RECEIVER_NOT_EXPORTED);
        Dbg.log("overlay", "screen receiver registered (OFF/ON/USER_PRESENT)");

        IntentFilter cmd = new IntentFilter();
        cmd.addAction(notificationobserver.ACTION_ITEMS);
        cmd.addAction(notificationobserver.ACTION_TEST);
        cmd.addAction(notificationobserver.ACTION_HIDE);
        cmd.addAction(notificationobserver.ACTION_REEVAL);
        cmd.addAction(notificationobserver.ACTION_AOD_SHOWN);
        cmd.addAction(notificationobserver.ACTION_AOD_GONE);
        cmd.addAction(notificationobserver.ACTION_WAKE);
        cmd.addAction(ACTION_ALARM_END);
        cmd.addAction(ACTION_ALARM_DRIFT);
        cmd.addAction(ACTION_ALARM_CLOCK);
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
        Dbg.log("overlay", "onDestroy " + state());
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(commandReceiver); } catch (Exception ignored) {}
        try { if (prefs != null) { prefs.unregisterOnSharedPreferenceChangeListener(this); } }
        catch (Exception ignored) {}
        stopBurst(false, "serviceDestroy");
        releaseAwakePanel(false);
        cancelAlarms();
        release(briefLock);
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
    public void onInterrupt() { Dbg.log("overlay", "onInterrupt"); }

    // ---- receivers -------------------------------------------------------------

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a))
            {
                Dbg.log("overlay", "recv SCREEN_OFF " + state());
                // The awake canvas keeps the panel on, so a screen-off while
                // it is up is the power button (or our own lock at the end of
                // a burst). A power-button press must stay authoritative: no
                // relaunch for a while, the light keeps working on the dozing
                // panel meanwhile.
                if (canvasShown && SystemClock.elapsedRealtime() - lastLockAt > 2500L)
                {
                    canvasBackoffUntil = SystemClock.elapsedRealtime() + 45000L;
                    Dbg.log("overlay", "power button while canvas up -> canvas paused 45s");
                }
                releaseAwakePanel(true);
                screenOn = false;
                // the evaluation below is 450 ms away, and the relock watch
                // polls for up to 1.5 s: keep the CPU for that window only
                briefCpu(2500L);
                syncOverlay();
                h.removeCallbacks(relockWatch);
                h.removeCallbacks(evaluateRunnable);
                h.postDelayed(evaluateRunnable, 450L); // let the system settle into doze
            }
            else if (Intent.ACTION_SCREEN_ON.equals(a))
            {
                Dbg.log("overlay", "recv SCREEN_ON " + state());
                screenOn = true;
                h.removeCallbacks(relockWatch);
                armKeyguardWatch();
                // The pause after a power-button press exists to stop an
                // off -> canvas -> on loop. Once the user has genuinely woken
                // the phone (not our own canvas launch), it is over.
                if (!canvasShown && canvasBackoffUntil > 0
                        && SystemClock.elapsedRealtime() - canvasLaunchAt > 3000L)
                {
                    canvasBackoffUntil = 0L;
                    Dbg.log("overlay", "user woke the phone -> canvas allowed again");
                }
                if (canvasShown)
                {
                    Dbg.log("overlay", "SCREEN_ON: canvas shown -> leave burst to canvas lifecycle");
                }
                else if (lockedScreenOnLightWanted())
                {
                    Dbg.log("overlay", "SCREEN_ON: locked+wanted -> keep/start ring (lightActive=" + lightActive + ")");
                    if (!lightActive)
                    {
                        startBurst(Prefs.timeoutSec(overlayservice.this) * 1000L, false);
                    }
                }
                else
                {
                    Dbg.log("overlay", "SCREEN_ON: not wanted -> stop");
                    stopBurst(false, "screenOn-notWanted");
                }
                h.removeCallbacks(screenOnRecheck);
                h.postDelayed(screenOnRecheck, 400L);
                syncOverlay();
            }
            else if (Intent.ACTION_USER_PRESENT.equals(a))
            {
                Dbg.log("overlay", "recv USER_PRESENT (unlock) " + state());
                handleUnlock("USER_PRESENT");
            }
            else if (Intent.ACTION_TIME_CHANGED.equals(a)
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(a)
                    || Intent.ACTION_LOCALE_CHANGED.equals(a))
            {
                // Refresh only when a clock line is actually shown: the
                // renderer no-ops when it is off, and armNextClock schedules
                // nothing. Re-arm so the next tick follows the new time.
                Dbg.log("overlay", "recv " + a + " -> clock refresh");
                if (view != null) { view.refreshClock(); }
                armNextClock();
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
            Dbg.log("overlay", "recv cmd " + a + " " + state());
            switch (a)
            {
                case notificationobserver.ACTION_ITEMS:
                {
                    ArrayList<NotificationItem> list =
                            intent.getParcelableArrayListExtra(notificationobserver.EXTRA_ITEMS);
                    items = list != null ? list : new ArrayList<>();
                    appNumbers = readAppNumbers(intent);
                    onItemsChanged();
                    break;
                }
                case notificationobserver.ACTION_TEST:
                    testMode = true;
                    testColor = intent.getIntExtra("color", Prefs.color(overlayservice.this));
                    startBurst(6000L, false);
                    break;
                case notificationobserver.ACTION_HIDE:
                    stopBurst(false, "cmdHide");
                    break;
                case notificationobserver.ACTION_REEVAL:
                    reevaluate();
                    break;
                case notificationobserver.ACTION_AOD_SHOWN:
                    canvasShown = true;
                    armKeyguardWatch();
                    Dbg.log("overlay", "AOD_SHOWN: canvasShown=true lightActive=" + lightActive);
                    if (!lightActive) { reevaluate(); }
                    syncOverlay();
                    break;
                case notificationobserver.ACTION_AOD_GONE:
                    // A canvas that disappears the instant it was shown means
                    // this device refuses it. Back off instead of relaunching
                    // on every screen-off, which would wake the panel in a loop.
                    if (canvasShown && SystemClock.elapsedRealtime() - canvasLaunchAt < 1500L)
                    {
                        canvasBackoffUntil = SystemClock.elapsedRealtime() + 30000L;
                        Dbg.log("overlay", "canvas vanished immediately -> backoff 30s (no wake loop)");
                    }
                    canvasShown = false;
                    boolean keep = screenOn && lockedScreenOnLightWanted()
                            && SystemClock.elapsedRealtime() - lastUserPresentAt >= 1500L;
                    Dbg.log("overlay", "AOD_GONE: keepOnLockScreen=" + keep);
                    if (keep)
                    {
                        if (!lightActive)
                        {
                            startBurst(Prefs.timeoutSec(overlayservice.this) * 1000L, false);
                        }
                    }
                    else
                    {
                        stopBurst(false, "aodGone");
                    }
                    syncOverlay();
                    break;
                case ACTION_ALARM_END:
                    Dbg.log("overlay", "alarm: end of burst");
                    briefCpu(2000L);
                    endBurst("alarm");
                    break;
                case ACTION_ALARM_DRIFT:
                    Dbg.log("overlay", "alarm: burn-in move");
                    briefCpu(2000L);
                    driftStep();
                    break;
                case ACTION_ALARM_CLOCK:
                    Dbg.log("overlay", "alarm: clock refresh");
                    briefCpu(2000L);
                    clockStep();
                    break;
                case notificationobserver.ACTION_WAKE:
                    Dbg.log("overlay", "WAKE: acquire wakeBridge 2s");
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
            Dbg.log("overlay", "screenOnRecheck " + state());
            if (!screenOn || canvasShown) { Dbg.log("overlay", "recheck: skip (screenOff or canvas)"); return; }
            if (lockedScreenOnLightWanted())
            {
                if (!lightActive)
                {
                    Dbg.log("overlay", "recheck: locked+wanted, start ring");
                    startBurst(Prefs.timeoutSec(overlayservice.this) * 1000L, false);
                }
            }
            else if (lightActive && !testMode)
            {
                Dbg.log("overlay", "recheck: not wanted, stop ring");
                stopBurst(false, "recheck-notWanted");
            }
        }
    };

    // Some devices never deliver ACTION_USER_PRESENT. While the light is on,
    // watch the keyguard directly: a locked -> unlocked transition is an
    // unlock no matter which broadcast did or did not arrive. Two consecutive
    // readings debounce any momentary flicker during wake/doze transitions.
    private final Runnable keyguardWatch = new Runnable()
    {
        @Override
        public void run()
        {
            if (!lightActive) { return; }
            if (testMode)
            {
                // "Test light" is pressed while the phone is unlocked, so the
                // keyguard is open by design. Treating that as an unlock cut
                // every test off after two polls (~600 ms).
                h.postDelayed(this, 300L);
                return;
            }
            boolean locked = km == null || km.isKeyguardLocked();
            // only an awake device can be "unlocked"; while the screen is off
            // the keyguard may lag a fresh lock, which is not an unlock
            boolean awake = screenOn || canvasShown;
            if (awake && !locked)
            {
                kmUnlockedSeen++;
                if (kmUnlockedSeen >= 2)
                {
                    kmUnlockedSeen = 0;
                    handleUnlock("keyguardWatch");
                    return;
                }
            }
            else
            {
                kmUnlockedSeen = 0;
            }
            lastKmLocked = locked;
            // An awake lock screen needs the fast poll to hide the light
            // promptly. While the panel is off there is nothing to unlock at
            // all, and every unlock arrives as SCREEN_ON, which re-arms this
            // watch - so polling a dozing phone would only wake the CPU.
            if (screenOn && !canvasShown)
            {
                h.postDelayed(this, 300L);   // real lock screen: hide instantly
            }
            else if (canvasShown)
            {
                // Our canvas sits over the keyguard, but a fingerprint unlock
                // leaves it on top, so the unlock still has to be noticed -
                // just not at the rate the real lock screen needs.
                h.postDelayed(this, 1000L);
            }
            else
            {
                Dbg.log("overlay", "keyguardWatch: panel off -> idle until SCREEN_ON");
            }
        }
    };

    // ---- state machine -------------------------------------------------------------

    private String state()
    {
        return "screenOn=" + screenOn + " canvas=" + canvasShown + " light=" + lightActive
                + " pending=" + burstPending + " items=" + items.size()
                + " kmLocked=" + (km == null ? "n/a" : km.isKeyguardLocked())
                + " disp=" + dispName(displayState())
                + " sinceUnlock=" + (lastUserPresentAt == 0 ? "never"
                        : (SystemClock.elapsedRealtime() - lastUserPresentAt) + "ms")
                + " test=" + testMode
                + " enabled=" + Prefs.enabled(this) + " aodLight=" + Prefs.aodLight(this)
                + " screenOnLight=" + Prefs.screenOnLight(this)
                + " relight=" + Prefs.relightRelock(this)
                + " fallbackCanvas=" + Prefs.canvasFallback(this)
                + " awakePanel=" + Prefs.awakePanel(this)
                + " visibility=" + Prefs.visibility(this)
                + " style=" + Prefs.style(this)
                + " blackoutFull=" + Prefs.blackoutFull(this);
    }

    private static String dispName(int st)
    {
        switch (st)
        {
            case Display.STATE_OFF: return "OFF";
            case Display.STATE_ON: return "ON";
            case Display.STATE_DOZE: return "DOZE";
            case Display.STATE_DOZE_SUSPEND: return "DOZE_SUSPEND";
            case Display.STATE_VR: return "VR";
            default: return "?" + st;
        }
    }

    private boolean effectiveScreenOff()
    {
        // Holding the display awake ourselves is still the always-on field:
        // the panel is on, but it shows our black field, not the lock screen.
        return !screenOn || canvasShown || awakeHeld;
    }

    /** Awake on the real lock screen (not our canvas): may the light show? */
    /**
     * True when the device is awake and unlocked, i.e. the light must not
     * show. The screen-off broadcast is the reliable "private" signal: some
     * devices always report Display.STATE_ON, and the keyguard can lag a
     * fresh lock by a second or two. While the screen is off we only treat
     * the brief stray-screen-off window right after an unlock as awake.
     */
    private boolean awakeUnlocked()
    {
        if (km == null) { return false; }
        if (km.isKeyguardLocked()) { return false; }
        if (screenOn || canvasShown) { return true; }
        return displayState() == Display.STATE_ON
                && SystemClock.elapsedRealtime() - lastUserPresentAt < 1500L;
    }

    private boolean lockedScreenOnLightWanted()
    {
        boolean wanted = Prefs.enabled(this) && Prefs.screenOnLight(this)
                && !items.isEmpty() && !suppressed()
                && km != null && km.isKeyguardLocked();
        Dbg.log("overlay", "lockedScreenOnLightWanted=" + wanted + " " + state());
        return wanted;
    }

    private boolean suppressed()
    {
        Calendar now = Calendar.getInstance();
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        return Prefs.inNightWindow(this, minute);
    }

    // After a recent unlock the keyguard can lag a fresh lock by a second or
    // two. Rather than guess from a time window, wait for the keyguard to
    // engage (a real relock). If the screen simply stays off and never wakes,
    // treat it as private/locked and light anyway; a genuinely awake device
    // would have produced a SCREEN_ON, which aborts this watch.
    private final Runnable relockWatch = new Runnable()
    {
        @Override
        public void run()
        {
            if (screenOn || canvasShown)
            { Dbg.log("overlay", "relockWatch: screen awake -> abort"); return; }
            if (items.isEmpty() || !Prefs.enabled(overlayservice.this) || !Prefs.aodLight(overlayservice.this))
            { Dbg.log("overlay", "relockWatch: nothing to show -> abort"); return; }
            if (km == null || km.isKeyguardLocked())
            {
                Dbg.log("overlay", "relockWatch: keyguard engaged -> startBurst");
                startBurst(Prefs.timeoutSec(overlayservice.this) * 1000L, true);
                return;
            }
            if (SystemClock.elapsedRealtime() - relockWatchStart > 1500L)
            {
                Dbg.log("overlay", "relockWatch: screen stayed off (keyguard lag) -> startBurst");
                startBurst(Prefs.timeoutSec(overlayservice.this) * 1000L, true);
                return;
            }
            h.postDelayed(this, 300L);
        }
    };

    private void armRelockWatch()
    {
        relockWatchStart = SystemClock.elapsedRealtime();
        h.removeCallbacks(relockWatch);
        h.postDelayed(relockWatch, 300L);
    }

    private void handleUnlock(String via)
    {
        Dbg.log("overlay", "handleUnlock via=" + via + " " + state());
        screenOn = true;
        lastUserPresentAt = SystemClock.elapsedRealtime();
        // A genuine unlock means a person is using the phone: the next dark
        // screen is a fresh start, so the awake panel may be tried again.
        awakeLateOffs = 0;
        awakeBackoffUntil = 0L;
        burstPending = false;
        stopBurst(false, "unlock:" + via);
        sendLocal(notificationobserver.ACTION_HIDE);
        if (Prefs.clearOnUnlock(this))
        {
            Dbg.log("overlay", "handleUnlock: clearOnUnlock -> CLEAR");
            sendLocal(notificationobserver.ACTION_CLEAR);
        }
    }

    private void onItemsChanged()
    {
        Dbg.log("overlay", "onItemsChanged " + state());
        if (testMode)
        {
            Dbg.log("overlay", "items: real notification supersedes test mode");
            testMode = false;
        }
        if (!Prefs.enabled(this)) { Dbg.log("overlay", "items: disabled -> stop"); stopBurst(false, "masterOff-items"); return; }

        if (items.isEmpty())
        {
            Dbg.log("overlay", "items: empty (all dismissed)");
            burstPending = false;
            if (lightActive) { endBurst("itemsEmpty"); }
            return;
        }

        String newestKey = items.get(0).key;
        boolean fresh = !newestKey.equals(lastBurstKey);
        Dbg.log("overlay", "items: newest=" + newestKey + " lastBurst=" + lastBurstKey
                + " fresh=" + fresh + " effScreenOff=" + effectiveScreenOff());

        if (effectiveScreenOff())
        {
            if (Prefs.aodLight(this) && !suppressed() && (fresh || !lightActive))
            {
                Dbg.log("overlay", "items: screen-off branch -> startBurst");
                startBurst(Prefs.timeoutSec(this) * 1000L, true);
            }
            else
            {
                Dbg.log("overlay", "items: screen-off branch skipped (aodLight="
                        + Prefs.aodLight(this) + " suppressed=" + suppressed()
                        + " fresh=" + fresh + " lightActive=" + lightActive + ")");
                if (lightActive)
                {
                    // The newest notification is the same one, but the set can
                    // still have changed: another notification from an app that
                    // is already shown, or one was dismissed. Refresh what is
                    // drawn (icons and counts) without restarting the burst
                    // or its timeout.
                    Dbg.log("overlay", "items: already lit -> refresh icons");
                    applyAppearance();
                }
            }
        }
        else if (fresh)
        {
            if (Prefs.screenOnLight(this) && km != null && km.isKeyguardLocked() && !suppressed())
            {
                Dbg.log("overlay", "items: screen-on locked branch -> startBurst");
                startBurst(Prefs.timeoutSec(this) * 1000L, false);
            }
            else
            {
                Dbg.log("overlay", "items: screen-on unlocked -> burstPending=true");
                burstPending = true; // show when the screen next turns off
            }
        }
        else if (lightActive)
        {
            Dbg.log("overlay", "items: same notification updated -> refresh appearance");
            applyAppearance();
        }
    }

    private void onScreenOff()
    {
        Dbg.log("overlay", "onScreenOff (delayed eval) " + state());
        if (!Prefs.enabled(this) || !Prefs.aodLight(this))
        { Dbg.log("overlay", "onScreenOff: disabled or aodLight off"); return; }
        if (items.isEmpty() || suppressed())
        { Dbg.log("overlay", "onScreenOff: no items or night-suppressed"); return; }
        if (!(lightActive || burstPending || Prefs.relightRelock(this)))
        { Dbg.log("overlay", "onScreenOff: no relight condition met"); return; }

        boolean locked = km == null || km.isKeyguardLocked();
        long since = SystemClock.elapsedRealtime() - lastUserPresentAt;
        if (!locked && since < 1500L)
        {
            // just after an unlock the keyguard may still be lagging a real
            // relock; wait for it to engage instead of guessing
            Dbg.log("overlay", "onScreenOff: keyguard lagging after recent unlock -> wait for relock");
            burstPending = false;
            armRelockWatch();
            return;
        }
        Dbg.log("overlay", "onScreenOff: -> startBurst (lightActive=" + lightActive
                + " pending=" + burstPending + " relight=" + Prefs.relightRelock(this) + ")");
        burstPending = false;
        startBurst(Prefs.timeoutSec(this) * 1000L, true);
    }

    private void reevaluate()
    {
        Dbg.log("overlay", "reevaluate " + state());
        if (!Prefs.enabled(this) || suppressed() || items.isEmpty())
        {
            Dbg.log("overlay", "reevaluate: blocked (enabled/suppressed/items)");
            if (lightActive) { endBurst("reeval-blocked"); }
            return;
        }
        if (effectiveScreenOff())
        {
            if (Prefs.aodLight(this) && !lightActive)
            {
                Dbg.log("overlay", "reevaluate: screen-off -> startBurst");
                startBurst(Prefs.timeoutSec(this) * 1000L, true);
            }
        }
        else if (km != null && km.isKeyguardLocked())
        {
            if (Prefs.screenOnLight(this))
            {
                if (!lightActive)
                {
                    Dbg.log("overlay", "reevaluate: screen-on locked -> startBurst");
                    startBurst(Prefs.timeoutSec(this) * 1000L, false);
                }
            }
            else if (lightActive && !testMode)
            {
                Dbg.log("overlay", "reevaluate: screenOnLight off -> stop");
                stopBurst(false, "reeval-screenOnLightOff");
            }
        }
        else
        {
            Dbg.log("overlay", "reevaluate: screen-on unlocked -> no action");
        }
    }

    // ---- burst control ----------------------------------------------------------------

    private void startBurst(long durationMs, boolean allowCanvas)
    {
        Dbg.log("overlay", "startBurst req dur=" + durationMs + " allowCanvas=" + allowCanvas + " " + state());
        if (view == null || !viewAdded) { Dbg.log("overlay", "startBurst REFUSED: no window"); return; }
        // backstop against stale events: never light up over an awake,
        // unlocked device, no matter which path asked for it
        if (!testMode && awakeUnlocked())
        {
            Dbg.log("overlay", "startBurst REFUSED: awake+unlocked backstop");
            return;
        }
        // the light geometry is portrait-only; skip in landscape
        try
        {
            if (wm.getDefaultDisplay().getRotation() != android.view.Surface.ROTATION_0)
            { Dbg.log("overlay", "startBurst REFUSED: landscape"); return; }
        }
        catch (Exception ignored) {}
        cancelTimers();
        awakeTried = false;

        int color = testMode ? testColor : items.isEmpty() ? Prefs.color(this) : items.get(0).color;
        if (!testMode && !items.isEmpty()) { lastBurstKey = items.get(0).key; }

        applyAppearance();
        refreshGeometry();
        view.setVisibility(android.view.View.VISIBLE);
        view.start();
        lightActive = true;
        lastKmLocked = km == null || km.isKeyguardLocked();
        kmUnlockedSeen = 0;
        armKeyguardWatch();

        boolean anim = animating();
        // A still image needs the CPU only while something is actually drawn:
        // the first frame here, then a short hold per later change. Holding it
        // for the whole burst kept the phone awake for minutes to show a
        // picture that never moves. An animating style does need it throughout.
        long lockFor = anim ? durationMs + 4000L : 1500L;
        burstEndsAt = SystemClock.elapsedRealtime() + durationMs;
        // A still image draws nothing between its rare frames, so its timers
        // can be alarms and the CPU is left to sleep. Anything that animates
        // or toggles keeps the Handler timers and the lock they need.
        timersViaAlarm = !anim && canExactAlarms();
        Dbg.log("overlay", "timers: path=" + (anim ? "animated" : "static")
                + " via=" + (timersViaAlarm ? "alarm" : "handler")
                + " exactAlarms=" + canExactAlarms()
                + " cpuLock=" + (needsCpuLock() ? "needed" : "none"));
        if (drawCapable && drawLock != null)
        {
            try { drawLock.acquire(lockFor); } catch (Exception ignored) {}
        }

        // The fallback canvas is an activity with setTurnScreenOn(true):
        // launching it here would immediately undo the user's power-button
        // screen-off (off -> canvas -> on -> off -> ...). It is therefore
        // opt-in only; by default the overlay draws everything and the
        // display is left exactly as the user left it.
        boolean wanted = canvasWanted();
        if (allowCanvas && wanted && !canvasShown && !screenOn)
        {
            Dbg.log("overlay", "startBurst -> launchCanvas (fallback=" + Prefs.canvasFallback(this)
                    + " awakePanel=" + Prefs.awakePanel(this) + ")");
            launchCanvas();
        }
        else if (allowCanvas)
        {
            Dbg.log("overlay", "startBurst canvas skipped: wanted=" + wanted
                    + " canvasShown=" + canvasShown + " screenOn=" + screenOn);
        }

        // The alarm is what wakes the phone at the right moment. The Handler
        // timer is armed as well: it costs nothing while the CPU sleeps and
        // ends the burst earlier if the phone happens to be awake anyway.
        // Whichever fires first wins; the other is cancelled in stopBurst().
        if (timersViaAlarm && !setAlarm(ACTION_ALARM_END, REQ_ALARM_END, durationMs))
        {
            timersViaAlarm = false;   // alarms unavailable: the Handler needs the CPU
        }
        armEndTimer(durationMs);
        armShift();
        armNextClock();
        syncCpuLock();
        syncOverlay();
        Dbg.log("overlay", "burst STARTED dur=" + durationMs + " canvas=" + allowCanvas
                + " color=#" + Integer.toHexString(color) + " " + state());
    }

    /** Handler-based end of a burst: for animations, and as the fallback
     *  when exact alarms are not available. */
    private void armEndTimer(long durationMs)
    {
        // Interval mode needs a tick to toggle visibility; otherwise a single
        // wake-up at the end of the burst is enough.
        long tick = Prefs.interval(this) ? 1000L : Math.max(1000L, durationMs);
        timeoutTimer = new CountDownTimer(durationMs, tick)
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
                            Dbg.log("overlay", "interval -> visible");
                            view.setVisibility(android.view.View.VISIBLE);
                            view.start();
                        }
                        else if (!visible && view.isRunning())
                        {
                            Dbg.log("overlay", "interval -> hidden");
                            view.stop();
                            view.setVisibility(android.view.View.GONE);
                        }
                    }
                }
            }

            @Override
            public void onFinish()
            {
                Dbg.log("overlay", "burst timeout finished");
                endBurst("timeout");
            }
        };
        timeoutTimer.start();
    }

    private void endBurst(String reason)
    {
        if (!lightActive)
        {
            Dbg.log("overlay", "endBurst reason=" + reason + " ignored: no burst");
            return;
        }
        boolean wasOff = effectiveScreenOff();
        boolean wasTest = testMode;
        Dbg.log("overlay", "endBurst reason=" + reason + " wasOff=" + wasOff + " wasTest=" + wasTest + " " + state());
        stopBurst(false, "endBurst:" + reason);
        testMode = false;
        if (wasOff && !wasTest)
        {
            Dbg.log("overlay", "endBurst -> HIDE + lockScreen");
            sendLocal(notificationobserver.ACTION_HIDE);
            lockScreen();
        }
    }

    private void stopBurst(boolean keepCanvas, String reason)
    {
        Dbg.log("overlay", "stopBurst reason=" + reason + " keepCanvas=" + keepCanvas + " " + state());
        h.removeCallbacks(screenOnRecheck);
        cancelTimers();
        lightActive = false;
        if (testMode)
        {
            // otherwise the test color and the demo icon would leak into
            // every later real notification
            Dbg.log("overlay", "test mode cleared by stopBurst (" + reason + ")");
            testMode = false;
        }
        if (view != null)
        {
            view.stop();
            view.clearIcons();
        }
        releaseAwakePanel(false);
        briefDraw(800L);    // one last frame to take the light off the panel
        syncOverlay();
        release(partialLock);
        cpuLockHeld = false;
        release(drawLock);
        if (!keepCanvas && canvasShown)
        {
            Dbg.log("overlay", "stopBurst -> send HIDE (canvas shown)");
            sendLocal(notificationobserver.ACTION_HIDE);
        }
    }

    private void cancelTimers()
    {
        cancelAlarms();
        if (timeoutTimer != null) { timeoutTimer.cancel(); timeoutTimer = null; }
        h.removeCallbacks(shiftRunnable);
        h.removeCallbacks(clockRunnable);
        h.removeCallbacks(keyguardWatch);
        h.removeCallbacks(relockWatch);
    }

    /** One rebuild for a burst of preference changes. Writing several keys
     *  in a single apply() notifies once per key, and the cutout report does
     *  exactly that with six of them. */
    private final Runnable appearanceRefresh = new Runnable()
    {
        @Override
        public void run()
        {
            if (!lightActive) { return; }
            applyAppearance();
            refreshGeometry();
            syncCpuLock();
        }
    };

    private final Runnable geometryRefresh = new Runnable()
    {
        @Override
        public void run() { refreshGeometry(); }
    };

    private final Runnable shiftRunnable = new Runnable()
    {
        @Override
        public void run() { driftStep(); }
    };

    /** Move the light one slot and arm the next move. */
    private void driftStep()
    {
        if (!lightActive || view == null) { return; }
        if (pinned()) { return; }
        applyShift();
        armNextShift();
    }

    private void armNextShift()
    {
        h.removeCallbacks(shiftRunnable);
        long interval = shiftIntervalMs();
        if (timersViaAlarm && setAlarm(ACTION_ALARM_DRIFT, REQ_ALARM_DRIFT, interval))
        {
            return;
        }
        h.postDelayed(shiftRunnable, interval);
    }

    /** The burn-in move interval chosen in settings (1/2/10/20 min). */
    private long shiftIntervalMs()
    {
        return Prefs.iconShiftMin(this) * 60000L;
    }

    // ---- clock refresh ----------------------------------------------------
    // The clock is part of the still icon image, so updating it means waking
    // the CPU and pushing one frame, exactly like the burn-in move. The time
    // ticks every minute; a date-only clock wakes once at midnight; with the
    // clock off (or a non-icon style) nothing is scheduled at all.

    private final Runnable clockRunnable = new Runnable()
    {
        @Override
        public void run() { clockStep(); }
    };

    private void clockStep()
    {
        if (!lightActive || view == null) { return; }
        briefDraw(800L);
        view.refreshClock();
        armNextClock();
    }

    private void armNextClock()
    {
        h.removeCallbacks(clockRunnable);
        cancelAlarm(ACTION_ALARM_CLOCK, REQ_ALARM_CLOCK);
        if (!lightActive || view == null) { return; }
        if (!Prefs.STYLE_ICONS.equals(Prefs.style(this))) { return; }
        long delay = ClockMath.nextRefreshMs(Prefs.clockMode(this), System.currentTimeMillis());
        if (delay <= 0L) { return; }   // clock off: no periodic wake
        Dbg.log("overlay", "clock: next refresh in " + (delay / 1000L) + "s");
        if (timersViaAlarm && setAlarm(ACTION_ALARM_CLOCK, REQ_ALARM_CLOCK, delay))
        {
            return;
        }
        h.postDelayed(clockRunnable, delay);
    }

    /** True when the icons style is switched to "pinned to one spot". */
    private boolean pinned()
    {
        return Prefs.STYLE_ICONS.equals(Prefs.style(this)) && !Prefs.iconShift(this);
    }

    /** Next position slot and draw it. */
    private void applyShift()
    {
        shiftIndex = (shiftIndex + 1) % SHIFTS.length;
        float d = getResources().getDisplayMetrics().density;
        // a subtle, sub-millimeter hop: just enough to hand the load to the
        // neighboring pixels (that is what prevents burn-in), small enough
        // to be barely noticeable. The same small step for every style.
        float dx = SHIFTS[shiftIndex][0] * d;
        float dy = SHIFTS[shiftIndex][1] * d;
        if (view != null) { briefDraw(800L); view.setShift(dx, dy); }
        Dbg.log("overlay", "burn-in shift slot=" + shiftIndex + " dx=" + (int) dx
                + " dy=" + (int) dy + " (next in " + Prefs.iconShiftMin(this) + " min)");
    }

    /**
     * Position the light for a new burst and arm the slow move for long
     * bursts. Stepping the slot once per burst is what protects the panel:
     * within a burst nothing moves at all, so the icons sit calmly for the
     * whole time they are shown, and repeated bursts land in different
     * places. With "move icons" switched off they stay pinned to one spot.
     */
    private void armShift()
    {
        h.removeCallbacks(shiftRunnable);
        cancelAlarm(ACTION_ALARM_DRIFT, REQ_ALARM_DRIFT);
        if (pinned())
        {
            shiftIndex = -1;      // slot 0 = no offset at all
            applyShift();
            return;
        }
        applyShift();
        armNextShift();
    }

    /**
     * Keep the display awake with our own window instead of an activity.
     *
     * A window flag needs no background activity launch, cannot be dismissed
     * by the keyguard, and has no lifecycle to lose - which is what killed the
     * canvas on some ROMs. The flag alone does not wake a sleeping panel, so
     * one short wake is requested per burst; after that the flag holds it.
     *
     * A screen-off while we hold it means either the power key or a ROM that
     * refuses to stay awake. How long the panel stayed up tells them apart:
     * a few seconds is the ROM, half a minute is a human. Never fight either
     * in a wake loop.
     */
    private void syncAwakePanel()
    {
        long now = SystemClock.elapsedRealtime();
        boolean want = lightActive && Prefs.awakePanel(this) && !canvasShown
                && now >= awakeBackoffUntil;
        if (want != awakeHeld)
        {
            awakeHeld = want;
            applyAwakeFlag(want);
            Dbg.log("overlay", "awake panel: " + (want ? "HOLDING display awake" : "released")
                    + " backoffLeft=" + Math.max(0L, awakeBackoffUntil - now) + "ms");
        }
        if (want && !awakeTried)
        {
            awakeTried = true;
            awakeAttemptAt = now;
            try { wakeBridge.acquire(1500L); } catch (Exception ignored) {}
            Dbg.log("overlay", "awake panel: one wake request sent");
        }
    }

    private void applyAwakeFlag(boolean on)
    {
        if (view == null || !viewAdded) { return; }
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) view.getLayoutParams();
        if (lp == null) { return; }
        int flags = on ? (lp.flags | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                : (lp.flags & ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (flags == lp.flags) { return; }
        lp.flags = flags;
        try { wm.updateViewLayout(view, lp); }
        catch (Exception e) { Log.w(TAG, "awake flag: " + e); }
    }

    private void releaseAwakePanel(boolean screenOff)
    {
        if (screenOff && awakeHeld)
        {
            long now = SystemClock.elapsedRealtime();
            long held = now - awakeAttemptAt;
            long pause;
            if (held < 12000L)
            {
                // Went back to sleep by itself within seconds: this ROM does
                // not allow an awake always-on field. Stop trying for a while.
                pause = 120000L;
                awakeLateOffs = 0;
            }
            else
            {
                // It stayed up and then went off: the power key. Honour it,
                // but only briefly - a later notification may light up again.
                // Repeated presses mean the user wants it dark: long pause.
                pause = (++awakeLateOffs >= 3) ? 120000L : 20000L;
            }
            awakeBackoffUntil = now + pause;
            awakeTried = false;   // one retry when the pause is over
            Dbg.log("overlay", "awake panel off after " + held + "ms -> pause "
                    + (pause / 1000L) + "s (" + (held < 12000L ? "ROM refused" : "power key")
                    + ", presses=" + awakeLateOffs + ")");
        }
        awakeHeld = false;
        applyAwakeFlag(false);
    }

    // ---- timers, wake locks ------------------------------------------------------

    /** Does this configuration produce frames or toggles of its own? */
    private boolean animating()
    {
        return Prefs.interval(this) || !Prefs.STYLE_ICONS.equals(Prefs.style(this));
    }

    /** Exact alarms need a permission from Android 12 on; without it the
     *  Handler timers and their wake lock are used instead. */
    private boolean canExactAlarms()
    {
        if (Build.VERSION.SDK_INT < 31) { return true; }
        try
        {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            return am != null && am.canScheduleExactAlarms();
        }
        catch (Throwable t) { return false; }
    }

    private PendingIntent alarmIntent(String action, int req)
    {
        Intent i = new Intent(action);
        i.setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** @return false when the alarm could not be set, so the caller can fall
     *          back to a Handler timer rather than lose the event. */
    private boolean setAlarm(String action, int req, long delayMs)
    {
        try
        {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) { return false; }
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs, alarmIntent(action, req));
            return true;
        }
        catch (Exception e)
        {
            Log.w(TAG, "alarm " + action + ": " + e);
            return false;
        }
    }

    private void cancelAlarm(String action, int req)
    {
        try
        {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) { am.cancel(alarmIntent(action, req)); }
        }
        catch (Exception ignored) {}
    }

    private void cancelAlarms()
    {
        cancelAlarm(ACTION_ALARM_END, REQ_ALARM_END);
        cancelAlarm(ACTION_ALARM_DRIFT, REQ_ALARM_DRIFT);
        cancelAlarm(ACTION_ALARM_CLOCK, REQ_ALARM_CLOCK);
    }

    /** An alarm guarantees the CPU only while the receiver runs; drawing a
     *  frame and locking the screen need a moment more. */
    private void briefCpu(long ms)
    {
        try { if (briefLock != null) { briefLock.acquire(ms); } }
        catch (Exception ignored) {}
    }

    /** Hold the draw lock just long enough to push one frame to a dozing
     *  panel. Every path that changes what is on screen calls this, so no
     *  long-held lock is needed anywhere for a still image. */
    private void briefDraw(long ms)
    {
        // An animating style already holds the lock for its whole run, and a
        // non-reference-counted acquire would *shorten* that hold.
        if (animating()) { return; }
        try { if (drawCapable && drawLock != null) { drawLock.acquire(ms); } }
        catch (Exception ignored) {}
    }

    private void armKeyguardWatch()
    {
        if (!lightActive) { return; }
        h.removeCallbacks(keyguardWatch);
        h.postDelayed(keyguardWatch, 300L);
    }

    /**
     * Hold the CPU awake only while something depends on it. An awake panel
     * already keeps the phone awake, and alarm-driven timers wake it on
     * their own - so a still light on a dozing panel needs no lock at all,
     * which is the point of the alarm path.
     */
    private void syncCpuLock()
    {
        boolean want = lightActive && needsCpuLock();
        if (want == cpuLockHeld) { return; }
        cpuLockHeld = want;
        if (want)
        {
            long left = Math.max(2000L, burstEndsAt - SystemClock.elapsedRealtime() + 4000L);
            try { partialLock.acquire(left); } catch (Exception ignored) {}
        }
        else
        {
            release(partialLock);
        }
        Dbg.log("overlay", "cpu lock " + (want ? "HELD" : "released")
                + " animated=" + animating() + " dark=" + effectiveScreenOff()
                + " alarms=" + timersViaAlarm);
    }

    private boolean needsCpuLock()
    {
        if (animating()) { return true; }
        if (!effectiveScreenOff()) { return false; }
        return !timersViaAlarm;
    }

    /**
     * Opaque black over the system AOD whenever the screen is off, so the
     * clock/widgets are hidden even with no notification. The ring draws on
     * top only while a burst is active. When idle this is a static black
     * field: no animation, no wake locks -> minimal CPU and battery. When
     * the screen is awake the overlay is transparent (or gone) so it never
     * covers the lock screen or launcher.
     */
    private void syncOverlay()
    {
        if (view == null || !viewAdded) { return; }
        syncAwakePanel();
        view.setBlackoutFull(Prefs.blackoutFull(this));
        boolean off = effectiveScreenOff();
        Dbg.log("overlay", "syncOverlay blackout=" + off + " lightActive=" + lightActive);
        view.setBlackout(off);
        if (off)
        {
            view.setVisibility(android.view.View.VISIBLE);
            if (!lightActive)
            {
                view.stop();
                view.clearIcons();
            }
        }
        else if (!lightActive)
        {
            view.stop();
            view.clearIcons();
            view.setVisibility(android.view.View.GONE);
        }
        else
        {
            view.setVisibility(android.view.View.VISIBLE);
        }
        syncCpuLock();
    }

    private void applyAppearance()
    {
        if (view == null) { return; }
        int color = testMode ? testColor : items.isEmpty() ? Prefs.color(this) : items.get(0).color;
        float strokePx = Prefs.strokeDp(this) * getResources().getDisplayMetrics().density;
        briefDraw(800L);
        view.configure(Prefs.style(this), Prefs.anim(this), color, Prefs.eco(this),
                Prefs.speedMs(this), strokePx, Prefs.dotX(this));
        view.setElementVisibility(Prefs.visibility(this) / 100f);
        syncIcons();
    }

    /**
     * Feed the renderer the icons of the apps that have a notification, one
     * per app, newest first. Icons are only used by the "app icons" style;
     * every other style keeps the drawn light. Nothing is shown when there
     * is no notification, so the idle screen stays completely black.
     */
    /** Per-app counters taken from the group summaries (app-wide totals). */
    private java.util.HashMap<String, Integer> appNumbers = new java.util.HashMap<>();

    @SuppressWarnings("unchecked")
    private static java.util.HashMap<String, Integer> readAppNumbers(Intent intent)
    {
        try
        {
            java.io.Serializable s = intent.getSerializableExtra(notificationobserver.EXTRA_APP_NUMBERS);
            if (s instanceof java.util.HashMap) { return (java.util.HashMap<String, Integer>) s; }
        }
        catch (Exception ignored) {}
        return new java.util.HashMap<>();
    }

    private void syncIcons()
    {
        if (view == null) { return; }
        view.setClock(ClockLayout.from(this));
        if (!Prefs.STYLE_ICONS.equals(Prefs.style(this)) || (!testMode && items.isEmpty()))
        {
            view.clearIcons();
            return;
        }
        IconLayout cfg = IconLayout.from(this);
        cfg.overUi = testMode;

        // One entry per app, newest app first. Per app we track how many
        // notifications it has and what its own counter (Notification.number)
        // says; LinkedHashMap keeps the arrival order.
        java.util.LinkedHashMap<String, Integer> perApp = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, Integer> own = new java.util.LinkedHashMap<>();
        // newest notification per app: it decides which symbol is drawn
        java.util.LinkedHashMap<String, NotificationItem> first = new java.util.LinkedHashMap<>();
        boolean fromApp = Prefs.iconCountFromApp(this);
        boolean add = Prefs.iconCountAdd(this);
        if (testMode)
        {
            // "Test light" previews our own icon with a demo count of 2, so the
            // number under the icon can be checked without a second device.
            perApp.put(getPackageName(), 2);
            own.put(getPackageName(), 0);
        }
        else
        {
            for (NotificationItem n : items)
            {
                if (n.batteryEvent || n.pkg == null || n.pkg.isEmpty()) { continue; }
                if (!first.containsKey(n.pkg)) { first.put(n.pkg, n); }
                Integer have = perApp.get(n.pkg);
                perApp.put(n.pkg, have == null ? 1 : have + 1);
                Integer num = own.get(n.pkg);
                int value = Math.max(0, n.number);
                if (num == null) { own.put(n.pkg, value); }
                else { own.put(n.pkg, IconMath.mergeOwn(num, value, add)); }
            }
        }

        String tint = Prefs.iconTint(this);
        boolean notifIcons = Prefs.iconFromNotification(this);
        ArrayList<android.graphics.Bitmap> list = new ArrayList<>();
        ArrayList<Integer> counts = new ArrayList<>();
        StringBuilder numbers = new StringBuilder();
        StringBuilder symbols = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : perApp.entrySet())
        {
            String pkg = e.getKey();
            NotificationItem item = first.get(pkg);
            android.graphics.Bitmap b = null;
            if (notifIcons && item != null && item.iconRes != 0)
            {
                int color = iconColorFor(pkg, item);
                b = AppIconCache.getNotificationIcon(this, pkg, item.iconRes, tint, cfg.sizePx, color);
                if (b != null)
                {
                    if (symbols.length() > 0) { symbols.append(','); }
                    symbols.append(pkg).append('=')
                            .append(String.format("#%08X", color));
                }
            }
            // no status-bar symbol (or it could not be loaded): the app icon
            if (b == null) { b = AppIconCache.get(this, pkg, tint, cfg.sizePx); }
            if (b != null)
            {
                list.add(b);
                counts.add(shownCount(pkg, e.getValue(), own.get(pkg), fromApp));
                if (numbers.length() > 0) { numbers.append(','); }
                numbers.append(e.getValue()).append('/').append(own.get(pkg));
            }
            if (list.size() >= cfg.maxIcons) { break; }
        }
        int[] nums = new int[counts.size()];
        for (int i = 0; i < nums.length; i++) { nums[i] = counts.get(i); }

        briefDraw(1200L);   // rasterising and drawing the icons
        view.setIcons(list, nums, cfg);
        Dbg.log("overlay", "icons: apps=" + perApp.size() + " drawn=" + list.size()
                + " tint=" + tint + " iconSrc=" + (notifIcons ? "notification" : "app")
                + " px=" + cfg.sizePx + " plate=" + cfg.plate
                + " shift=" + Prefs.iconShift(this) + " counts=" + counts
                + " src=" + (fromApp ? "app" : "notifications") + (add ? "+sum" : "+max")
                + " notif/own=[" + numbers + "] summary=" + appNumbers
                + (symbols.length() > 0 ? " symbolColors=[" + symbols + "]" : "")
                + " layout[" + cfg + "]");
    }

    /**
     * The color to fill a status-bar symbol with, best guess first:
     *
     *  1. the color the user picked for that app (long press in the picker),
     *  2. Notification.color - the accent the app itself chose for its own
     *     notification icon, when it is a real color and not white/grey/black,
     *  3. the dominant color of that app's launcher icon (already resolved by
     *     the observer into NotificationItem.color), or the global color.
     */
    private int iconColorFor(String pkg, NotificationItem item)
    {
        int override = Prefs.appColor(this, pkg);
        if (override != android.graphics.Color.TRANSPARENT) { return override; }
        if (item != null && usableAccent(item.accent)) { return item.accent; }
        return item != null ? item.color : Prefs.color(this);
    }

    /** An accent is only useful when it is opaque and actually colored. */
    private static boolean usableAccent(int color)
    {
        if (android.graphics.Color.alpha(color) < 250) { return false; }
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        return hsv[1] >= 0.15f && hsv[2] >= 0.12f;
    }

    /**
     * The number to print under one app's icon.
     *
     * In "notifications" mode it is simply how many notifications that app
     * has. In "app" mode the app's own counter (Notification.number, possibly
     * only published on its group summary) is used when it has one -- but never
     * less than the number of notifications, so an app that always writes 1
     * cannot hide the fact that three notifications are waiting. Apps that
     * publish nothing (0) fall back to the notification count.
     */
    private int shownCount(String pkg, int notifications, Integer ownCounter, boolean fromApp)
    {
        Integer summary = appNumbers.get(pkg);
        return IconMath.shownCount(notifications, ownCounter == null ? 0 : ownCounter,
                summary == null ? 0 : summary, fromApp);
    }

    private void refreshGeometry()
    {
        if (view == null) { return; }
        Rect cam = CutoutLocator.cameraRect(this);
        Point size = CutoutLocator.realSize(this);
        briefDraw(800L);
        view.setGeometry(cam, size);
        view.setBlackoutFull(Prefs.blackoutFull(this));
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

    /**
     * Does the dark field need the awake canvas? Only when the user asked
     * for it as a fallback on phones that show nothing while the display is
     * off. "Keep the panel awake" uses a window flag instead, not an
     * activity. The panel brightness is the phone's business either way: a
     * dozing panel uses the ROM's fixed doze level, and the items are faded
     * by the visibility setting while drawing, never by a brightness request.
     */
    private boolean canvasWanted()
    {
        return Prefs.canvasFallback(this);
    }

    private void launchCanvas()
    {
        long now = SystemClock.elapsedRealtime();
        if (now < canvasBackoffUntil)
        {
            Dbg.log("overlay", "launchCanvas skipped: backoff " + (canvasBackoffUntil - now) + "ms");
            return;
        }
        canvasLaunchAt = now;
        try
        {
            Intent intent = new Intent(this, AodScreenActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            PendingIntent pi = PendingIntent.getActivity(this, 1, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            pi.send();
            Dbg.log("overlay", "launchCanvas: PendingIntent.send()");
        }
        catch (Exception e)
        {
            Log.w(TAG, "canvas launch failed: " + e);
        }
    }

    private void lockScreen()
    {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLockAt < LOCK_GUARD_MS)
        { Dbg.log("overlay", "lockScreen: guarded (too soon)"); return; }
        lastLockAt = now;
        Dbg.log("overlay", "lockScreen: GLOBAL_ACTION_LOCK_SCREEN");
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
        Dbg.log("overlay", "pref changed: " + key);
        if (Prefs.ENABLED.equals(key))
        {
            if (!Prefs.enabled(this))
            {
                stopBurst(false, "masterOff-pref");
                sendLocal(notificationobserver.ACTION_HIDE);
                try { disableSelf(); } catch (Exception ignored) {}
            }
            return;
        }
        if (Prefs.NIGHT.equals(key) || Prefs.NIGHT_START.equals(key) || Prefs.NIGHT_END.equals(key))
        {
            sendLocal(notificationobserver.ACTION_REEVAL);
        }
        if (Prefs.AOD_VISIBILITY.equals(key) && view != null)
        {
            view.setElementVisibility(Prefs.visibility(this) / 100f);
        }
        if (Prefs.AWAKE_PANEL.equals(key))
        {
            if (canvasWanted() && !screenOn && !canvasShown && lightActive)
            {
                Dbg.log("overlay", "awake panel wanted -> canvas needed now");
                launchCanvas();
            }
        }
        if (Prefs.BLACKOUT_FULL.equals(key))
        {
            Dbg.log("overlay", "blackout extent changed: full=" + Prefs.blackoutFull(this));
            refreshGeometry();
            syncOverlay();
        }
        // every icon setting (appearance, position, spacing, counts) is an
        // "icon_*" key, so one branch covers them all
        if (Prefs.STYLE.equals(key) || (key != null && key.startsWith("icon_")))
        {
            applyAppearance();
            refreshGeometry();
            if (lightActive) { armShift(); }
        }
        if (key != null && key.startsWith("clock_"))
        {
            // the clock is part of the still icon image: hand the renderer
            // the new configuration directly, no full rebuild needed
            if (view != null) { view.setClock(ClockLayout.from(this)); }
            if (lightActive) { armNextClock(); }
        }
        if (Prefs.CANVAS_FALLBACK.equals(key) || Prefs.AWAKE_PANEL.equals(key))
        {
            if (!canvasWanted() && canvasShown)
            {
                Dbg.log("overlay", "awake canvas no longer wanted -> HIDE (panel may doze)");
                sendLocal(notificationobserver.ACTION_HIDE);
            }
        }
        if (lightActive && key != null && !key.startsWith("cutout_"))
        {
            // geometry-only keys are handled below; everything else may change
            // what is drawn, so rebuild once for the whole batch
            h.removeCallbacks(appearanceRefresh);
            h.postDelayed(appearanceRefresh, 60L);
        }
        if (key != null && key.startsWith("cutout_"))
        {
            // the camera report writes six of these at once
            h.removeCallbacks(geometryRefresh);
            h.postDelayed(geometryRefresh, 60L);
        }
    }
}
