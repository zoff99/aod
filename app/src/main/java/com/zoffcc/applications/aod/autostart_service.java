package com.zoffcc.applications.aod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class autostart_service extends BroadcastReceiver
{
    private static final String TAG = "D7:autostart";

    @Override
    public void onReceive(Context context, Intent intent)
    {
        final String intent_action = intent.getAction();
        if (intent_action.equals("android.intent.action.BOOT_COMPLETED"))
        {
            Log.i(TAG, "onReceive: action=" + intent_action + " intent=" + intent);
        }
        else
        {
            Log.i(TAG, "onReceive:recevied other intent: action=" + intent_action + " intent=" + intent);
        }
    }
}
