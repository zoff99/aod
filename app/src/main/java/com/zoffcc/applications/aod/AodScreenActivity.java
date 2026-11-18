package com.zoffcc.applications.aod;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;

/**
 * A pure-black, very dim, lock-screen-visible activity that acts as a
 * replacement always-on canvas on devices where overlays are not composited
 * during native doze (or which have no AOD at all).
 *
 * Properties that make this work without root:
 *  - setShowWhenLocked(true) + setTurnScreenOn(true): visible over the keyguard;
 *  - FLAG_KEEP_SCREEN_ON: the panel stays lit while the burst runs, but the
 *    window brightness is turned far down and every pixel except the light
 *    is black — on OLED this is nearly free;
 *  - the notification light itself is drawn by the accessibility overlay
 *    window, which sits above this activity;
 *  - double tap anywhere wakes the device properly;
 *  - when the burst ends the overlay service locks the screen again, which
 *    pauses and finishes this activity, letting the device doze.
 *
 * This canvas shows no notification text: the app never reads titles or
 * message bodies (see PRIVACY.md). It is a black field, nothing more.
 */
public class AodScreenActivity extends Activity
{
    private static final long DOUBLE_TAP_MS = 450L;

    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private long lastTap = 0L;
    private boolean receiversRegistered = false;

    private final Runnable selfTimeout = new Runnable()
    {
        @Override
        public void run() { Dbg.log("canvas", "selfTimeout -> finish"); finish(); }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String a = intent.getAction();
            if (notificationobserver.ACTION_HIDE.equals(a))
            {
                Dbg.log("canvas", "recv HIDE -> finish");
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        Dbg.log("canvas", "onCreate");

        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                              WindowManager.LayoutParams.MATCH_PARENT);

        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        int brightness = Math.max(5, Math.min(100, Prefs.brightness(this)));
        attrs.screenBrightness = (brightness / 100f) * 0.45f;
        getWindow().setAttributes(attrs);

        buildUi();
        overridePendingTransition(0, 0);
        resetTimeout();
    }

    private void buildUi()
    {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        setContentView(root);

        if (Prefs.doubleTapWake(this))
        {
            root.setOnTouchListener(new View.OnTouchListener()
            {
                @Override
                public boolean onTouch(View v, MotionEvent event)
                {
                    if (event.getAction() == MotionEvent.ACTION_UP)
                    {
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastTap < DOUBLE_TAP_MS)
                        {
                            wakeUp();
                            return true;
                        }
                        lastTap = now;
                    }
                    return true;
                }
            });
        }
    }

    private void wakeUp()
    {
        Dbg.log("canvas", "wakeUp (double tap) -> WAKE + finish");
        Intent wake = new Intent(notificationobserver.ACTION_WAKE);
        wake.setPackage(getPackageName());
        sendBroadcast(wake);
        finish();
        overridePendingTransition(0, 0);
    }

    private void resetTimeout()
    {
        h.removeCallbacks(selfTimeout);
        h.postDelayed(selfTimeout, (Prefs.timeoutSec(this) + 5L) * 1000L);
    }

    @Override
    protected void onNewIntent(Intent intent)
    {
        super.onNewIntent(intent);
        Dbg.log("canvas", "onNewIntent");
        resetTimeout();
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        if (!receiversRegistered)
        {
            IntentFilter f = new IntentFilter();
            f.addAction(notificationobserver.ACTION_HIDE);
            ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiversRegistered = true;
        }
        // report the real cutout geometry for the ring style
        View decor = getWindow().getDecorView();
        decor.post(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    CutoutLocator.reportInsets(AodScreenActivity.this,
                            decor.getRootWindowInsets());
                }
                catch (Exception ignored) {}
            }
        });
        Dbg.log("canvas", "onResume -> AOD_SHOWN");
        sendLocal(notificationobserver.ACTION_AOD_SHOWN);
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        // one-shot canvas: whenever something else takes the foreground
        // (unlock, power key, our own lock-screen action), we are done.
        Dbg.log("canvas", "onPause -> AOD_GONE + finish");
        sendLocal(notificationobserver.ACTION_AOD_GONE);
        h.removeCallbacks(selfTimeout);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy()
    {
        Dbg.log("canvas", "onDestroy");
        if (receiversRegistered)
        {
            try { unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiversRegistered = false;
        }
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void sendLocal(String action)
    {
        Intent i = new Intent(action);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private int dp(int v)
    {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
