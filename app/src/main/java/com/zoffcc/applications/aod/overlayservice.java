package com.zoffcc.applications.aod;

import android.accessibilityservice.AccessibilityService;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.Nullable;

public class overlayservice extends AccessibilityService
{
    private static final String TAG = "D7:olaysrv";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event)
    {
        Log.i(TAG, "onAccessibilityEvent: event=" + event.getPackageName() + " " + event);
    }

    @Override
    public void onCreate()
    {
        super.onCreate();
        Log.i(TAG, "onCreate");
    }

    public void onDestroy()
    {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
    }

    @Override
    public void onInterrupt()
    {
        Log.i(TAG, "onInterrupt");
    }

    @Override
    public int onStartCommand(Intent paramIntent, int paramInt1, int paramInt2)
    {
        Log.i(TAG, "onStartCommand" + paramIntent + " " + paramInt1 + " " + paramInt2);
        return super.onStartCommand(paramIntent, paramInt1, paramInt2);
    }

    @Override
    protected void onServiceConnected()
    {
        super.onServiceConnected();
        Log.i(TAG, "onServiceConnected");
    }

    @Override
    public boolean onUnbind(Intent intent)
    {
        Log.i(TAG, "onUnbind");
        return super.onUnbind(intent);
    }
}
