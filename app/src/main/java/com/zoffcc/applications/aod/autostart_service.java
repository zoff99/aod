package com.zoffcc.applications.aod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Re-arms the pipeline after boot and forwards night-boundary alarms.
 * Both system-bound services (notification listener, accessibility) are
 * re-created by the system automatically once enabled; we only nudge the
 * listener to rebind and ask everyone to re-evaluate state.
 */
public class autostart_service extends BroadcastReceiver
{
    private static final String TAG = "D7:autostart";

    @Override
    public void onReceive(Context context, Intent intent)
    {
        final String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onReceive: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action))
        {
            notificationobserver.requestRebind(context);
        }

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            notificationobserver.ACTION_REEVAL.equals(action))
        {
            Intent reeval = new Intent(notificationobserver.ACTION_REEVAL);
            reeval.setPackage(context.getPackageName());
            context.sendBroadcast(reeval);
        }
    }
}
