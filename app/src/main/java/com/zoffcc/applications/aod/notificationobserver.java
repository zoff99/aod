package com.zoffcc.applications.aod;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.Intent;
import android.os.IBinder;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import androidx.annotation.Nullable;

import static com.zoffcc.applications.aod.CustomListAdapter.logo_list;
import static com.zoffcc.applications.aod.MainActivity.adapter;
import static com.zoffcc.applications.aod.MainActivity.append_dbg;
import static com.zoffcc.applications.aod.MainActivity.long_date_time_format_or_empty;
import static com.zoffcc.applications.aod.notificationobserver.change_type.NOTI_ADD;
import static com.zoffcc.applications.aod.notificationobserver.change_type.NOTI_REMOVE;

public class notificationobserver extends NotificationListenerService
{
    private static final String TAG = "D7:ntfobs";

    public static final class change_type
    {
        public static final int NOTI_NONE = 0;
        public static final int NOTI_ADD = 1;
        public static final int NOTI_REMOVE = 2;
    }

    public final static String NOTI_UNKNOWN_PKG = "______.______";

    public static class notification_data
    {
        int Id;
        String PackageName;
        int flags;
    }

    static final HashMap<Integer, notification_data> notis = new HashMap<Integer, notification_data>();

    @Nullable
    @Override
    public IBinder onBind(Intent intent)
    {
        Log.i(TAG, "onBind");
        return super.onBind(intent);
    }

    public void load_current_notis()
    {
        StatusBarNotification[] activeNotifications = getActiveNotifications();
        if (activeNotifications != null && activeNotifications.length > 0)
        {
            for (StatusBarNotification activeNotification : activeNotifications)
            {
                if (!is_notification_forground_or_noclear(activeNotification))
                {
                    Log.i(TAG, "matchNotificationCode: LOAD : type=" + NOTI_REMOVE + " id=" + activeNotification.getId() +
                               " pkg=" + activeNotification.getPackageName());
                    append_dbg("R:" + long_date_time_format_or_empty(System.currentTimeMillis()) + ":" +
                               activeNotification.getPackageName() + "\n");
                    Intent intent = new Intent("com.zoffcc.applications.aod");
                    intent.putExtra("Notification Code", activeNotification.getPackageName());
                    sendBroadcast(intent);
                    add_to_notis(activeNotification);
                }
            }
        }
    }

    @SuppressWarnings("WhileLoopReplaceableByForEach")
    public static void dump_notis()
    {
        try
        {
            Log.i(TAG, "dump_notis:=============== start ===============");
            Iterator<Map.Entry<Integer, notification_data>> it = notis.entrySet().iterator();
            while (it.hasNext())
            {
                Map.Entry<Integer, notification_data> nd = it.next();
                Log.i(TAG, "dump_notis:" + nd.getValue());
            }
            Log.i(TAG, "dump_notis:===============  end  ===============");
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void add_to_notis(StatusBarNotification activeNotification)
    {
        try
        {
            synchronized (notis)
            {
                final int id = activeNotification.getId();
                notification_data nd = new notification_data();
                nd.Id = id;
                nd.flags = activeNotification.getNotification().flags;
                nd.PackageName = activeNotification.getPackageName();
                notis.put(id, nd);
                logo_list.put(id, new CustomListAdapter.noti_logo_item(nd.PackageName, id));
                adapter.notifyDataSetChanged();
            }
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
        dump_notis();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void rm_from_notis(StatusBarNotification sbn)
    {
        try
        {
            synchronized (notis)
            {
                int id = sbn.getId();
                notis.remove(id);
                logo_list.remove(id);
                adapter.notifyDataSetChanged();
            }
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
        dump_notis();
    }

    public static boolean is_notification_forground_or_noclear(final StatusBarNotification sbn)
    {
        Log.i(TAG, "is_fg:" + sbn.getNotification().flags + " " + (sbn.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE));
        if ((sbn.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0)
        {
            return true;
        }
        Log.i(TAG, "is_fg:" + sbn.getNotification().flags + " " + (sbn.getNotification().flags & Notification.FLAG_NO_CLEAR));
        if ((sbn.getNotification().flags & Notification.FLAG_NO_CLEAR) != 0)
        {
            return true;
        }
        if (sbn.getPackageName().equals("android"))
        {
            return true;
        }
        return false;
    }

    public static boolean is_same_noti(final StatusBarNotification sbn1, final StatusBarNotification sbn2)
    {
        if (sbn1.getId() != sbn2.getId())
        {
            Log.i(TAG, "is_same_noti " + sbn1.getId() + " " + sbn2.getId());
            return false;
        }
        if (!sbn1.getPackageName().equals(sbn2.getPackageName()))
        {
            Log.i(TAG, "is_same_noti " + sbn1.getPackageName() + " " + sbn2.getPackageName());
            return false;
        }
        return true;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn)
    {
        try
        {
            if (!is_notification_forground_or_noclear(sbn))
            {
                Log.i(TAG, "onNotificationPosted: GOOD :" + sbn.getId() + " " + sbn.getNotification());
                final String notificationPkg = matchNotificationCode(sbn, NOTI_ADD);
                append_dbg("A:" + long_date_time_format_or_empty(System.currentTimeMillis()) + ":" + notificationPkg +
                           "\n");
                Intent intent = new Intent("com.zoffcc.applications.aod");
                intent.putExtra("Notification Code", notificationPkg);
                sendBroadcast(intent);
                add_to_notis(sbn);
            }
            else
            {
                Log.i(TAG, "onNotificationPosted: _FG_ :" + sbn.getId() + " " + sbn.getNotification());
            }
        }
        catch(Exception e)
        {
            Log.i(TAG, "onNotificationPosted:EE:" + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn)
    {
        try
        {
            if (is_notification_forground_or_noclear(sbn))
            {
                Log.i(TAG, "onNotificationRemoved: _FG_ :" + sbn.getId() + " " + sbn.getNotification());
                return;
            }
            else
            {
                Log.i(TAG, "matchNotificationCode: GOOD : type=" + NOTI_REMOVE + " id=" + sbn.getId()
                           + " pkg=" + sbn.getPackageName());
                append_dbg("R:" + long_date_time_format_or_empty(System.currentTimeMillis()) + ":" +
                           sbn.getPackageName() + "\n");
                Intent intent = new Intent("com.zoffcc.applications.aod");
                intent.putExtra("Notification Code", NOTI_UNKNOWN_PKG);
                sendBroadcast(intent);
                rm_from_notis(sbn);
            }

            /*
            final String notificationPkg = matchNotificationCode(sbn, NOTI_NONE);
            StatusBarNotification[] activeNotifications = this.getActiveNotifications();
            if (activeNotifications != null && activeNotifications.length > 0)
            {
                Log.i(TAG, "matchNotificationCode:CHECK1");
                for (StatusBarNotification activeNotification : activeNotifications)
                {
                    Log.i(TAG, "matchNotificationCode:CHECK2");
                    if (is_same_noti(activeNotification, sbn))
                    {
                        Log.i(TAG, "matchNotificationCode: GOOD : type=" + NOTI_REMOVE + " id=" + sbn.getId() + " pkg=" + activeNotification.getPackageName());
                        append_dbg("R:" + long_date_time_format_or_empty(System.currentTimeMillis()) + ":" +
                                   notificationPkg + "\n");
                        Intent intent = new Intent("com.zoffcc.applications.aod");
                        intent.putExtra("Notification Code", notificationPkg);
                        sendBroadcast(intent);
                        break;
                    }
                }
            }
            */
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public void onListenerConnected()
    {
        super.onListenerConnected();

        notis.clear();
        load_current_notis();
    }

    @Override
    public void onListenerDisconnected()
    {
        super.onListenerDisconnected();
    }

    @Override
    public void onSilentStatusBarIconsVisibilityChanged(boolean hideSilentStatusIcons)
    {
        super.onSilentStatusBarIconsVisibilityChanged(hideSilentStatusIcons);
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
    }

    private String matchNotificationCode(StatusBarNotification sbn, int type) {
        try
        {
            String packageName = sbn.getPackageName();
            //if (type != NOTI_NONE) {
            //    Log.i(TAG, "matchNotificationCode: type=" + type + " pkg=" + packageName);
            //}
            return packageName;
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
        return NOTI_UNKNOWN_PKG;
    }
}
