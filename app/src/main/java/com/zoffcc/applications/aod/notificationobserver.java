package com.zoffcc.applications.aod;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notification source of the app. A system-bound NotificationListenerService:
 * it needs no foreground service, no overlay permission and no root.
 *
 * It keeps an ordered map of actionable notifications, enriches them with a
 * light color (per-app override -> icon dominant color -> default), injects
 * synthetic battery events, and broadcasts the resulting list to the overlay
 * renderer and to the UI.
 *
 * Suppression rules applied here: foreground/persistent notifications,
 * progress updates, media sessions, group summaries, system packages,
 * the app filter, and Do-Not-Disturb. Night hours are applied by the
 * overlay service (it also owns the screen state).
 */
public class notificationobserver extends NotificationListenerService
{
    private static final String TAG = "D7:ntfobs";

    public static final String ACTION_ITEMS = "com.zoffcc.applications.aod.ITEMS";
    public static final String ACTION_TEST = "com.zoffcc.applications.aod.TEST";
    public static final String ACTION_HIDE = "com.zoffcc.applications.aod.HIDE";
    public static final String ACTION_CLEAR = "com.zoffcc.applications.aod.CLEAR";
    public static final String ACTION_REEVAL = "com.zoffcc.applications.aod.REEVAL";
    public static final String ACTION_AOD_SHOWN = "com.zoffcc.applications.aod.AOD_SHOWN";
    public static final String ACTION_AOD_GONE = "com.zoffcc.applications.aod.AOD_GONE";
    public static final String ACTION_WAKE = "com.zoffcc.applications.aod.WAKE";
    public static final String EXTRA_ITEMS = "items";
    public static final String EXTRA_APP_NUMBERS = "app_numbers";


    public static final String PKG_CHARGING = "aod.battery.charging";
    public static final String PKG_FULL = "aod.battery.full";
    public static final String PKG_LOW = "aod.battery.low";

    /** Latest snapshot, also used by the UI. Never null. */
    public static volatile List<NotificationItem> latest = new ArrayList<>();

    private final LinkedHashMap<String, NotificationItem> items = new LinkedHashMap<>();
    /** Group summaries: never shown as an icon, but their Notification.number
     *  is often the only place an app publishes its app-wide unread total. */
    private final LinkedHashMap<String, NotificationItem> summaries = new LinkedHashMap<>();
    private final HashMap<String, Integer> iconColorCache = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock briefLock;
    private battery_receiver batteryReceiver;
    private command_receiver commandReceiver;
    private String batteryState = "";
    private boolean connected = false;

    private final Runnable publishRunnable = new Runnable()
    {
        @Override
        public void run() { publish(); }
    };

    // ---- lifecycle ---------------------------------------------------------

    @Override
    public void onListenerConnected()
    {
        super.onListenerConnected();
        connected = true;
        Log.i(TAG, "onListenerConnected");
        Dbg.log("observer", "onListenerConnected");

        briefLock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aod:ntf");
        briefLock.setReferenceCounted(false);

        batteryReceiver = new battery_receiver();
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = registerReceiver(batteryReceiver, f);
        if (sticky != null) { handleBattery(sticky); }

        commandReceiver = new command_receiver();
        IntentFilter cf = new IntentFilter();
        cf.addAction(ACTION_CLEAR);
        cf.addAction(ACTION_REEVAL);
        androidx.core.content.ContextCompat.registerReceiver(this, commandReceiver, cf,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);

        items.clear();
        synchronized (summaries) { summaries.clear(); }
        try
        {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null)
            {
                for (StatusBarNotification sbn : active) { consider(sbn); }
            }
        }
        catch (Exception e) { Log.w(TAG, "load active: " + e); }
        publish();
        scheduleNightBoundary();
    }

    @Override
    public void onListenerDisconnected()
    {
        Dbg.log("observer", "onListenerDisconnected");
        connected = false;
        super.onListenerDisconnected();
        try { if (batteryReceiver != null) { unregisterReceiver(batteryReceiver); } }
        catch (Exception ignored) {}
        batteryReceiver = null;
        try { if (commandReceiver != null) { unregisterReceiver(commandReceiver); } }
        catch (Exception ignored) {}
        commandReceiver = null;
    }

    @Override
    public void onDestroy()
    {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- notification events -------------------------------------------------

    @Override
    public void onNotificationPosted(StatusBarNotification sbn)
    {
        acquireBrief();
        boolean accepted = consider(sbn);
        Notification posted = sbn.getNotification();
        boolean summary = posted != null && (posted.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        Dbg.log("observer", "posted pkg=" + sbn.getPackageName() + " key=" + sbn.getKey()
                + " number=" + (posted == null ? 0 : posted.number)
                + " accepted=" + accepted + (summary ? " (summary: counter only, no icon)" : ""));
        if (accepted)
        {
            handler.removeCallbacks(publishRunnable);
            handler.post(publishRunnable);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn)
    {
        acquireBrief();
        boolean changed;
        synchronized (items)
        {
            changed = items.remove(sbn.getKey()) != null;
        }
        synchronized (summaries)
        {
            if (summaries.remove(sbn.getKey()) != null) { changed = true; }
        }
        Dbg.log("observer", "removed pkg=" + sbn.getPackageName() + " key=" + sbn.getKey()
                + " changed=" + changed);
        if (changed)
        {
            handler.removeCallbacks(publishRunnable);
            handler.postDelayed(publishRunnable, 300); // coalesce removal bursts
        }
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter)
    {
        // DND state changed -> re-publish so the light reacts immediately
        handler.removeCallbacks(publishRunnable);
        handler.post(publishRunnable);
    }

    private void acquireBrief()
    {
        try
        {
            if (briefLock != null) { briefLock.acquire(1000L); }
        }
        catch (Exception ignored) {}
    }

    /** @return true when the map changed. */
    private boolean consider(StatusBarNotification sbn)
    {
        try
        {
            if (sbn == null) { return false; }
            String pkg = sbn.getPackageName();
            if (pkg == null || pkg.equals(getPackageName()) || pkg.equals("android")) { return false; }
            if (!Prefs.appAllowed(this, pkg)) { return false; }

            Notification n = sbn.getNotification();
            if (n == null) { return false; }

            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0)
            {
                return rememberSummary(sbn.getKey(), pkg, n.number);
            }
            if (Prefs.ignorePersistent(this) &&
                ((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0 ||
                 (n.flags & Notification.FLAG_NO_CLEAR) != 0 || !sbn.isClearable()))
            {
                return false;
            }

            Bundle extras = n.extras;
            if (extras != null)
            {
                // skip download/progress notifications
                if (extras.containsKey("android.progress"))
                {
                    int p = extras.getInt("android.progress", -1);
                    int max = extras.getInt("android.progressMax", 0);
                    if (max > 0 && p > 0 && p < max) { return false; }
                }
                // skip media players
                if (extras.getParcelable("android.mediaSession") != null) { return false; }
                String template = extras.getString("android.template");
                if (template != null && template.contains("MediaStyle")) { return false; }
            }

            NotificationItem item = new NotificationItem();
            item.pkg = pkg;
            item.key = sbn.getKey();
            item.postTime = sbn.getPostTime();
            if (extras != null)
            {
                CharSequence t = extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence x = extras.getCharSequence(Notification.EXTRA_TEXT);
                if (x == null) { x = extras.getCharSequence(Notification.EXTRA_BIG_TEXT); }
                item.title = t == null ? "" : t.toString().trim();
                item.text = x == null ? "" : x.toString().trim();
            }
            item.number = Math.max(0, n.number);
            item.color = resolveColor(pkg);

            synchronized (items)
            {
                items.put(item.key, item);
            }
            return true;
        }
        catch (Exception e)
        {
            Log.w(TAG, "consider: " + e);
            return false;
        }
    }

    /**
     * Store a group summary's counter. Returns true when the value changed, so
     * a new app-wide total republishes even though no icon changes.
     */
    private boolean rememberSummary(String key, String pkg, int number)
    {
        int value = Math.max(0, number);
        synchronized (summaries)
        {
            NotificationItem old = summaries.get(key);
            if (old != null && old.number == value && old.pkg.equals(pkg)) { return false; }
            NotificationItem s = new NotificationItem();
            s.pkg = pkg;
            s.key = key;
            s.number = value;
            summaries.put(key, s);
        }
        Dbg.log("observer", "summary counter pkg=" + pkg + " number=" + value);
        return true;
    }

    /** Highest summary counter per package, for the icon counts. */
    private HashMap<String, Integer> summaryNumbers()
    {
        HashMap<String, Integer> out = new HashMap<>();
        synchronized (summaries)
        {
            for (NotificationItem s : summaries.values())
            {
                Integer have = out.get(s.pkg);
                if (have == null || s.number > have) { out.put(s.pkg, s.number); }
            }
        }
        return out;
    }

    private int resolveColor(String pkg)
    {
        int override = Prefs.appColor(this, pkg);
        if (override != android.graphics.Color.TRANSPARENT) { return override; }

        int def = Prefs.color(this);
        if (!Prefs.autoColor(this)) { return def; }

        Integer cached = iconColorCache.get(pkg);
        if (cached != null) { return cached; }

        int color = def;
        try
        {
            Drawable icon = getPackageManager().getApplicationIcon(pkg);
            color = ColorUtil.dominantColor(icon, def);
        }
        catch (Exception ignored) {}
        iconColorCache.put(pkg, color);
        return color;
    }

    // ---- battery events --------------------------------------------------------

    private class battery_receiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction()))
            {
                handleBattery(intent);
            }
        }
    }

    private void handleBattery(Intent intent)
    {
        try
        {
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            int pct = scale > 0 ? level * 100 / scale : 0;
            boolean charging = plugged != 0 && status == BatteryManager.BATTERY_STATUS_CHARGING;
            boolean full = plugged != 0 && (status == BatteryManager.BATTERY_STATUS_FULL || pct >= 100);
            boolean low = plugged == 0 && pct <= 15;

            String newState = !Prefs.batteryLight(this) ? "" : full ? PKG_FULL : charging ? PKG_CHARGING : low ? PKG_LOW : "";
            if (newState.equals(batteryState)) { return; }
            batteryState = newState;
            Dbg.log("observer", "batteryState=" + (newState.isEmpty() ? "(none)" : newState));

            synchronized (items)
            {
                items.remove(PKG_CHARGING);
                items.remove(PKG_FULL);
                items.remove(PKG_LOW);
                if (!newState.isEmpty())
                {
                    NotificationItem b = new NotificationItem();
                    b.pkg = newState;
                    b.key = newState;
                    b.batteryEvent = true;
                    b.postTime = System.currentTimeMillis();
                    if (PKG_FULL.equals(newState))
                    {
                        b.title = getString(R.string.battery_full);
                        b.color = 0xFF7CFC00;
                    }
                    else if (PKG_CHARGING.equals(newState))
                    {
                        b.title = getString(R.string.battery_charging, pct);
                        b.color = 0xFF00E676;
                    }
                    else
                    {
                        b.title = getString(R.string.battery_low, pct);
                        b.color = 0xFFFF3D00;
                    }
                    items.put(b.key, b);
                }
            }
            handler.removeCallbacks(publishRunnable);
            handler.post(publishRunnable);
        }
        catch (Exception e) { Log.w(TAG, "battery: " + e); }
    }

    // ---- publishing ---------------------------------------------------------------

    private boolean dndSuppressed()
    {
        try
        {
            return Prefs.respectDnd(this) &&
                   getCurrentInterruptionFilter() != INTERRUPTION_FILTER_ALL;
        }
        catch (Exception e) { return false; }
    }

    private void publish()
    {
        ArrayList<NotificationItem> list = new ArrayList<>();
        if (!dndSuppressed())
        {
            synchronized (items)
            {
                list.addAll(items.values());
            }
            // newest first
            java.util.Collections.sort(list, (a, b) -> Long.compare(b.postTime, a.postTime));
        }
        latest = list;

        HashMap<String, Integer> numbers = summaryNumbers();
        Intent intent = new Intent(ACTION_ITEMS);
        intent.setPackage(getPackageName());
        intent.putParcelableArrayListExtra(EXTRA_ITEMS, list);
        intent.putExtra(EXTRA_APP_NUMBERS, numbers);
        sendBroadcast(intent);
        Log.i(TAG, "publish: " + list.size() + " item(s)");
        Dbg.log("observer", "publish n=" + list.size() + " dnd=" + dndSuppressed()
                + " newest=" + (list.isEmpty() ? "-" : list.get(0).key)
                + " summaryNumbers=" + numbers);
    }

    // ---- night boundary alarm ---------------------------------------------------------

    private void scheduleNightBoundary()
    {
        try
        {
            if (!Prefs.night(this)) { return; }
            Calendar now = Calendar.getInstance();
            int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            int start = Prefs.nightStart(this);
            int end = Prefs.nightEnd(this);
            int next = nextBoundary(minute, start, end);
            if (next < 0) { return; }
            Calendar target = (Calendar) now.clone();
            target.set(Calendar.HOUR_OF_DAY, next / 60);
            target.set(Calendar.MINUTE, next % 60);
            target.set(Calendar.SECOND, 0);
            if (!target.after(now)) { target.add(Calendar.DAY_OF_YEAR, 1); }

            Intent i = new Intent(this, autostart_service.class);
            i.setAction(ACTION_REEVAL);
            PendingIntent pi = PendingIntent.getBroadcast(this, 77, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + (target.getTimeInMillis() - now.getTimeInMillis()), pi);
        }
        catch (Exception e) { Log.w(TAG, "night alarm: " + e); }
    }

    private static int nextBoundary(int minute, int start, int end)
    {
        int best = -1;
        for (int b : new int[]{start, end})
        {
            int delta = (b - minute + 1440) % 1440;
            if (delta == 0) { delta = 1440; }
            if (best < 0 || delta < best) { best = delta; }
        }
        return best < 0 ? -1 : (minute + best) % 1440;
    }

    // ---- commands from the overlay / UI ------------------------------------------------

    private class command_receiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String a = intent.getAction();
            Dbg.log("observer", "recv cmd " + a);
            if (ACTION_CLEAR.equals(a))
            {
                clearAllNotifications();
            }
            else if (ACTION_REEVAL.equals(a))
            {
                iconColorCache.clear();
                batteryState = "";
                handler.removeCallbacks(publishRunnable);
                handler.post(publishRunnable);
                scheduleNightBoundary();
            }
        }
    }

    /** Registered by the overlay service; handled here via a static helper. */
    public void clearAllNotifications()
    {
        Dbg.log("observer", "clearAllNotifications");
        try
        {
            cancelAllNotifications();
            synchronized (items) { items.clear(); }
            publish();
        }
        catch (Exception e) { Log.w(TAG, "clearAll: " + e); }
    }

    public static void requestRebind(Context c)
    {
        try
        {
            android.content.ComponentName cn =
                    new android.content.ComponentName(c, notificationobserver.class);
            NotificationListenerService.requestRebind(cn);
        }
        catch (Exception e) { Log.w(TAG, "rebind: " + e); }
    }
}
