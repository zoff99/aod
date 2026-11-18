package com.zoffcc.applications.aod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Re-arms the pipeline after boot and after app updates, and forwards
 * night-boundary alarms.
 * Both system-bound services (notification listener, accessibility) are
 * re-created by the system automatically once enabled; we only nudge the
 * listener to rebind and ask everyone to re-evaluate state.
 */
public class autostart_service extends BroadcastReceiver
{
    private static final String TAG = Dbg.TAG_PREFIX + "Boot";

    @Override
    public void onReceive(Context context, Intent intent)
    {
        final String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onReceive: " + action);

        // After an update install the system rebinds enabled listeners on
        // its own, but on some builds that rebind is lost; ask explicitly.
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))
        {
            // An update can leave the accessibility service switched off. Put
            // it back, but only if the user granted WRITE_SECURE_SETTINGS via
            // adb and the app is switched on (see AccessRestore).
            AccessRestore.restore(context,
                    Intent.ACTION_BOOT_COMPLETED.equals(action) ? "boot" : "update");
            notificationobserver.requestRebind(context);
        }

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
            notificationobserver.ACTION_REEVAL.equals(action))
        {
            Intent reeval = new Intent(notificationobserver.ACTION_REEVAL);
            reeval.setPackage(context.getPackageName());
            context.sendBroadcast(reeval);
        }
    }
}
