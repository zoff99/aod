package com.zoffcc.applications.aod;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;

import static com.zoffcc.applications.aod.notificationobserver.NOTI_UNKNOWN_PKG;

/*
 *
 *  dummy notification with adb:
 *  adb shell cmd notification post -S bigtext -t 'Title' 'Tag' 'Multiline text'
 *
 */

public class MainActivity extends AppCompatActivity
{
    private static final String TAG = "D7:main";

    private static final String ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners";
    private static final String ACTION_NOTIFICATION_LISTENER_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";

    private ImageView interceptedNotificationImageView;
    private ImageChangeBroadcastReceiver imageChangeBroadcastReceiver;
    @SuppressWarnings("FieldCanBeLocal")
    private AlertDialog enableNotificationListenerAlertDialog;
    static PackageManager pm = null;

    @SuppressLint("StaticFieldLeak")
    public static TextView debug_text = null;
    Handler main_handler = null;
    static Handler main_handler_s = null;
    @SuppressLint("SimpleDateFormat")
    final static SimpleDateFormat df_date_time_long = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    static CustomListAdapter adapter = null;
    private static int OVERLAY_PERM_REQUEST_CODE = 18937;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        Log.i(TAG, "onCreate:start");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, true));
        adapter = new CustomListAdapter();
        recyclerView.setAdapter(adapter);

        try
        {
            pm = getPackageManager();
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }

        // Here we get a reference to the image we will modify when a notification is received
        ////**//// interceptedNotificationImageView = this.findViewById(R.id.logos_listview);
        debug_text = this.findViewById(R.id.debug_text);

        main_handler = new Handler(getMainLooper());
        main_handler_s = main_handler;

        append_dbg("start\n");

        // If the user did not turn the notification listener service on we prompt him to do so
        if (!isNotificationServiceEnabled())
        {
            enableNotificationListenerAlertDialog = buildNotificationServiceAlertDialog();
            enableNotificationListenerAlertDialog.show();
        }

        //if (checkDrawOverlayPermission())
        //{
        //    startOverlay();
        //}

        // Finally we register a receiver to tell the MainActivity when a notification has been received
        imageChangeBroadcastReceiver = new ImageChangeBroadcastReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("com.zoffcc.applications.aod");
        registerReceiver(imageChangeBroadcastReceiver, intentFilter);

        // Intent ovs_intent = new Intent(this, overlayservice.class);
        // startService(ovs_intent);

        AlwaysOnService aos = new AlwaysOnService();
        aos.st();

        Log.i(TAG, "onCreate:done");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(imageChangeBroadcastReceiver);
    }

    /**
     * Since Android 6.0 we need to ask for the permission to show our app above other apps
     */
    private boolean checkDrawOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.i(TAG, "canDrawOverlays NOK");
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                           Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERM_REQUEST_CODE);
                return false;
            } else {
                Log.i(TAG, "canDrawOverlays OK");
            }
        }
        return true;
    }

    @Override
    @TargetApi(Build.VERSION_CODES.M)
    protected void onActivityResult(int requestCode, int resultCode,  Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERM_REQUEST_CODE)
        {
            if (Settings.canDrawOverlays(this))
            {
                startOverlay();
            }
        }
    }

    private void startOverlay()
    {
        startPowerOverlay();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void startPowerOverlay(){
        // Starts the button overlay.
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        ImageView overlayPowerBtn = new ImageView(this);
        overlayPowerBtn.setImageResource(R.drawable.whatsapp_logo);

        int LAYOUT_FLAG;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // APPLICATION_OVERLAY FOR ANDROID 26+ AS THE PREVIOUS VERSION RAISES ERRORS
            LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            // FOR PREVIOUS VERSIONS USE TYPE_PHONE AS THE NEW VERSION IS NOT SUPPORTED
            LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                LAYOUT_FLAG,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;
        params.height = 110;
        params.width = 110;

        windowManager.addView(overlayPowerBtn, params);

        overlayPowerBtn.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private long latestPressTime = 0;

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // Save current x/y
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        // Check for double clicks.
                        if (latestPressTime == 0 || latestPressTime + 500 < System.currentTimeMillis()) {
                            latestPressTime = System.currentTimeMillis();
                        } else {
                            // Doubleclicked. Do any action you'd like
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(overlayPowerBtn, params);
                        return true;
                }
                return false;
            }
        });
    }


    /**
     * Change Intercepted Notification Image
     * Changes the MainActivity image based on which notification was intercepted
     * @param notificationCode The intercepted notification code
     */
    private void changeInterceptedNotificationImage(final String notificationCode) {
        Log.i(TAG, "changeInterceptedNotificationImage: notificationCode=" + notificationCode);
        try
        {
            if (notificationCode.equals(NOTI_UNKNOWN_PKG))
            {
                //**//interceptedNotificationImageView.setImageResource(R.drawable.ic_launcher_background);
                return;
            }
            Drawable icon = pm.getApplicationIcon(notificationCode);
            //**//interceptedNotificationImageView.setImageDrawable(icon);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            //**//interceptedNotificationImageView.setImageResource(R.drawable.other_notification_logo);
        }
    }

    /**
     * Is Notification Service Enabled.
     * Verifies if the notification listener service is enabled.
     * @return True if enabled, false otherwise.
     */
    private boolean isNotificationServiceEnabled(){
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(),
                                                      ENABLED_NOTIFICATION_LISTENERS);
        if (!TextUtils.isEmpty(flat))
        {
            final String[] names = flat.split(":");
            for (String name : names)
            {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null)
                {
                    if (TextUtils.equals(pkgName, cn.getPackageName()))
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public class ImageChangeBroadcastReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Log.i(TAG, "onReceive:" + intent);
            final String receivedNotificationCode = intent.getStringExtra("Notification Code");
            changeInterceptedNotificationImage(receivedNotificationCode);
        }
    }

    /**
     * Build Notification Listener Alert Dialog.
     * Builds the alert dialog that pops up if the user has not turned
     * the Notification Listener Service on yet.
     * @return An alert dialog which leads to the notification enabling screen
     */
    private AlertDialog buildNotificationServiceAlertDialog(){
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setTitle(R.string.notification_listener_service);
        alertDialogBuilder.setMessage(R.string.notification_listener_service_explanation);
        alertDialogBuilder.setPositiveButton(R.string.yes,
                                             (dialog, id) -> startActivity(new Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        alertDialogBuilder.setNegativeButton(R.string.no, (dialog, id) -> {});
        return(alertDialogBuilder.create());
    }

    static String long_date_time_format_or_empty(long timestamp_in_millis)
    {
        try
        {
            return df_date_time_long.format(new Date(timestamp_in_millis));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return "";
        }
    }


    public static void append_dbg(final String text)
    {
        Runnable myRunnable = new Runnable()
        {
            @SuppressLint("SetTextI18n")
            @Override
            public void run()
            {
                try
                {
                    try
                    {
                        if (debug_text.getText().length() > 20000)
                        {
                            debug_text.setText("");
                        }
                    }
                    catch(Exception e)
                    {
                        e.printStackTrace();
                    }
                    debug_text.append(text);
                }
                catch (Exception e)
                {
                    Log.i(TAG, "append_dbg:EE:" + e.getMessage());
                    e.printStackTrace();
                }
            }
        };

        if (main_handler_s != null)
        {
            main_handler_s.post(myRunnable);
        }
    }
}