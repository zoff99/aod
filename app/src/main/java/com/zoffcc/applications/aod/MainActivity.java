package com.zoffcc.applications.aod;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/*
 * Test notifications without a second device:
 *   adb shell cmd notification post -S bigtext -t 'Title' 'Tag' 'Multiline text'
 */
public class MainActivity extends AppCompatActivity implements CustomListAdapter.OnItemLongClick
{
    private static final String TAG = "D7:main";

    @SuppressLint("StaticFieldLeak")
    public static TextView debug_text = null;
    static Handler main_handler_s = null;
    @SuppressLint("SimpleDateFormat")
    final static SimpleDateFormat df_date_time_long = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    static CustomListAdapter adapter = null;

    private final Handler main_handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView status_text;
    private items_receiver itemsReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Prefs.get(this);

        // let this window see the real cutout so "detect" can measure it
        try
        {
            WindowManager.LayoutParams a = getWindow().getAttributes();
            a.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(a);
        }
        catch (Exception ignored) {}

        debug_text = findViewById(R.id.debug_text);
        main_handler_s = main_handler;
        status_text = findViewById(R.id.status_text);

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        adapter = new CustomListAdapter();
        adapter.longClickListener = this;
        recyclerView.setAdapter(adapter);

        bindPermissions();
        bindSwitches();
        bindSpinners();
        bindColorRow();
        bindSliders();
        bindTextInputs();
        bindButtons();

        itemsReceiver = new items_receiver();
        IntentFilter f = new IntentFilter(notificationobserver.ACTION_ITEMS);
        ContextCompat.registerReceiver(this, itemsReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);

        append_dbg("start " + BuildConfig.GitHash + "\n");
        refreshItems(notificationobserver.latest);

        if (!isNotificationServiceEnabled())
        {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.notification_listener_service)
                    .setMessage(R.string.notification_listener_service_explanation)
                    .setPositiveButton(R.string.yes,
                            (d, w) -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                    .setNegativeButton(R.string.no, null)
                    .show();
        }
    }

    // ---- permissions -------------------------------------------------------

    private void bindPermissions()
    {
        findViewById(R.id.btn_notif_access).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        findViewById(R.id.btn_accessibility).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btn_battery).setOnClickListener(v -> requestBatteryExemption());

        Button auto = findViewById(R.id.btn_auto_enable);
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED)
        {
            auto.setVisibility(View.VISIBLE);
            auto.setOnClickListener(v -> autoEnableAccessibility());
        }
    }

    private void requestBatteryExemption()
    {
        try
        {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(getPackageName()))
            {
                Toast.makeText(this, R.string.on, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }
        catch (Exception e)
        {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception ignored) {}
        }
    }

    /** Only possible when WRITE_SECURE_SETTINGS was granted via ADB. */
    private void autoEnableAccessibility()
    {
        try
        {
            ComponentName cn = new ComponentName(this, overlayservice.class);
            String flat = cn.flattenToString();
            String current = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
            String updated;
            if (TextUtils.isEmpty(current)) { updated = flat; }
            else if (current.contains(flat)) { updated = current; }
            else { updated = current + ":" + flat; }
            Settings.Secure.putString(getContentResolver(), "enabled_accessibility_services", updated);
            Settings.Secure.putInt(getContentResolver(), "accessibility_enabled", 1);
            Toast.makeText(this, R.string.on, Toast.LENGTH_SHORT).show();
        }
        catch (Exception e)
        {
            Toast.makeText(this, String.valueOf(e), Toast.LENGTH_LONG).show();
        }
    }

    private boolean isNotificationServiceEnabled()
    {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private boolean isAccessibilityEnabled()
    {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
        return flat != null && flat.contains(new ComponentName(this, overlayservice.class).flattenToString());
    }

    private void updateStatus()
    {
        boolean bat;
        try
        {
            bat = ((PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName());
        }
        catch (Exception e) { bat = false; }
        String on = getString(R.string.on);
        String off = getString(R.string.off);
        boolean listener = isNotificationServiceEnabled();
        boolean access = isAccessibilityEnabled();
        String text = getString(R.string.status_line,
                listener ? on : off, access ? on : off, bat ? on : off);
        SpannableString sp = new SpannableString(text);
        int from = 0;
        for (boolean ok : new boolean[]{listener, access, bat})
        {
            String token = ok ? on : off;
            int idx = text.indexOf(token, from);
            if (idx >= 0)
            {
                sp.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this,
                                ok ? R.color.hc_good : R.color.hc_bad)),
                        idx, idx + token.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                from = idx + token.length();
            }
        }
        status_text.setText(sp);
    }

    // ---- settings wiring ------------------------------------------------------

    private void bindSwitch(int id, String key, boolean def)
    {
        SwitchCompat sw = findViewById(id);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(key, checked).apply());
    }

    private void bindSwitches()
    {
        bindSwitch(R.id.sw_enabled, Prefs.ENABLED, true);
        bindSwitch(R.id.sw_aod_light, Prefs.AOD_LIGHT, true);
        bindSwitch(R.id.sw_screen_on_light, Prefs.SCREEN_ON_LIGHT, true);
        bindSwitch(R.id.sw_preview, Prefs.PREVIEW, true);
        bindSwitch(R.id.sw_double_tap, Prefs.DOUBLE_TAP_WAKE, true);
        bindSwitch(R.id.sw_clear_unlock, Prefs.CLEAR_ON_UNLOCK, false);
        bindSwitch(R.id.sw_relight_relock, Prefs.RELIGHT_RELOCK, true);
        bindSwitch(R.id.sw_canvas_fallback, Prefs.CANVAS_FALLBACK, false);
        bindSwitch(R.id.sw_icon_plate, Prefs.ICON_PLATE, false);
        bindSwitch(R.id.sw_icon_shift, Prefs.ICON_SHIFT, true);
        bindSwitch(R.id.sw_auto_color, Prefs.AUTO_COLOR, true);
        bindSwitch(R.id.sw_eco, Prefs.ECO, false);
        bindSwitch(R.id.sw_interval, Prefs.INTERVAL, false);
        bindSwitch(R.id.sw_night, Prefs.NIGHT, false);
        bindSwitch(R.id.sw_dnd, Prefs.RESPECT_DND, true);
        bindSwitch(R.id.sw_battery_light, Prefs.BATTERY_LIGHT, true);
        bindSwitch(R.id.sw_ignore_persistent, Prefs.IGNORE_PERSISTENT, true);
    }

    private void bindSpinners()
    {
        Spinner style = findViewById(R.id.sp_style);
        ArrayAdapter<CharSequence> sa = ArrayAdapter.createFromResource(this,
                R.array.style_entries, R.layout.item_spinner);
        sa.setDropDownViewResource(R.layout.item_spinner_dropdown);
        style.setAdapter(sa);
        String[] styleVals = {Prefs.STYLE_RING, Prefs.STYLE_EDGE, Prefs.STYLE_DOT, Prefs.STYLE_ICONS};
        style.setSelection(indexOf(styleVals, prefs.getString(Prefs.STYLE, Prefs.STYLE_RING)));
        style.setOnItemSelectedListener(new SimpleItemSelected()
        {
            @Override
            public void onItemSelected(int position)
            {
                prefs.edit().putString(Prefs.STYLE, styleVals[position]).apply();
            }
        });

        Spinner anim = findViewById(R.id.sp_anim);
        ArrayAdapter<CharSequence> aa = ArrayAdapter.createFromResource(this,
                R.array.anim_entries, R.layout.item_spinner);
        aa.setDropDownViewResource(R.layout.item_spinner_dropdown);
        anim.setAdapter(aa);
        String[] animVals = {Prefs.ANIM_PULSE, Prefs.ANIM_ROTATE, Prefs.ANIM_FLASH, Prefs.ANIM_STEADY};
        anim.setSelection(indexOf(animVals, prefs.getString(Prefs.ANIM, Prefs.ANIM_PULSE)));
        anim.setOnItemSelectedListener(new SimpleItemSelected()
        {
            @Override
            public void onItemSelected(int position)
            {
                prefs.edit().putString(Prefs.ANIM, animVals[position]).apply();
            }
        });

        Spinner filter = findViewById(R.id.sp_filter);
        ArrayAdapter<CharSequence> fa = ArrayAdapter.createFromResource(this,
                R.array.filter_entries, R.layout.item_spinner);
        fa.setDropDownViewResource(R.layout.item_spinner_dropdown);
        filter.setAdapter(fa);
        filter.setSelection(Prefs.filterSelected(this) ? 1 : 0);
        filter.setOnItemSelectedListener(new SimpleItemSelected()
        {
            @Override
            public void onItemSelected(int position)
            {
                prefs.edit().putString(Prefs.FILTER_MODE, position == 1 ? "selected" : "all").apply();
                sendLocal(notificationobserver.ACTION_REEVAL);
            }
        });

        Spinner tint = findViewById(R.id.sp_icon_tint);
        ArrayAdapter<CharSequence> ta = ArrayAdapter.createFromResource(this,
                R.array.icon_tint_entries, R.layout.item_spinner);
        ta.setDropDownViewResource(R.layout.item_spinner_dropdown);
        tint.setAdapter(ta);
        String[] tintVals = {Prefs.ICON_TINT_COLOR, Prefs.ICON_TINT_WHITE, Prefs.ICON_TINT_BLACK};
        tint.setSelection(indexOf(tintVals, prefs.getString(Prefs.ICON_TINT, Prefs.ICON_TINT_COLOR)));
        tint.setOnItemSelectedListener(new SimpleItemSelected()
        {
            @Override
            public void onItemSelected(int position)
            {
                prefs.edit().putString(Prefs.ICON_TINT, tintVals[position]).apply();
            }
        });
    }

    private static int indexOf(String[] arr, String v)
    {
        for (int i = 0; i < arr.length; i++) { if (arr[i].equals(v)) { return i; } }
        return 0;
    }

    private abstract static class SimpleItemSelected implements android.widget.AdapterView.OnItemSelectedListener
    {
        public abstract void onItemSelected(int position);

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id)
        {
            onItemSelected(position);
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }

    private void bindColorRow()
    {
        LinearLayout row = findViewById(R.id.color_row);
        int size = dp(40);
        int margin = dp(5);
        int current = prefs.getInt(Prefs.COLOR, ColorDialogs.PRESET_COLORS[0]);
        for (int i = 0; i < ColorDialogs.PRESET_COLORS.length; i++)
        {
            final int color = ColorDialogs.PRESET_COLORS[i];
            Button b = new Button(this);
            b.setBackground(colorCircle(color, color == current));
            b.setContentDescription(getString(ColorDialogs.PRESET_NAMES[i]));
            b.setStateListAnimator(null);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, margin, margin, margin);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                prefs.edit().putInt(Prefs.COLOR, color).apply();
                for (int j = 0; j < row.getChildCount(); j++)
                {
                    row.getChildAt(j).setBackground(colorCircle(ColorDialogs.PRESET_COLORS[j],
                            ColorDialogs.PRESET_COLORS[j] == color));
                }
            });
            row.addView(b);
        }
    }

    private Drawable colorCircle(int color, boolean selected)
    {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        int ring = selected ? (color == 0xFFFFFFFF ? 0xFF00E5FF : 0xFFFFFFFF) : 0xFF6E6E73;
        gd.setStroke(dp(selected ? 4 : 2), ring);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(0x40000000), gd, mask);
    }

    private interface SliderWriter { void write(int progress); }

    private void bindSlider(int id, int labelId, int initial, int formatRes, SliderWriter writer)
    {
        SeekBar sb = findViewById(id);
        TextView lbl = findViewById(labelId);
        sb.setProgress(initial);
        lbl.setText(getString(formatRes, initial));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                lbl.setText(getString(formatRes, progress));
                if (fromUser) { writer.write(progress); }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void bindSliders()
    {
        bindSlider(R.id.sb_timeout, R.id.lbl_timeout,
                prefs.getInt(Prefs.TIMEOUT_SEC, 60) - 10, R.string.lbl_timeout,
                p -> prefs.edit().putInt(Prefs.TIMEOUT_SEC, p + 10).apply());
        bindSlider(R.id.sb_brightness, R.id.lbl_brightness,
                prefs.getInt(Prefs.BRIGHTNESS, 35) - 5, R.string.lbl_brightness,
                p -> prefs.edit().putInt(Prefs.BRIGHTNESS, p + 5).apply());
        bindSlider(R.id.sb_stroke, R.id.lbl_stroke,
                prefs.getInt(Prefs.STROKE_DP, 5) - 2, R.string.lbl_stroke,
                p -> prefs.edit().putInt(Prefs.STROKE_DP, p + 2).apply());
        bindSlider(R.id.sb_icon_size, R.id.lbl_icon_size,
                prefs.getInt(Prefs.ICON_SIZE_DP, 48) - 24, R.string.lbl_icon_size,
                p -> prefs.edit().putInt(Prefs.ICON_SIZE_DP, p + 24).apply());
        bindSlider(R.id.sb_speed, R.id.lbl_speed,
                (prefs.getInt(Prefs.SPEED_MS, 1600) - 300) / 100, R.string.lbl_speed,
                p -> prefs.edit().putInt(Prefs.SPEED_MS, 300 + p * 100).apply());
        bindSlider(R.id.sb_cutout_x, R.id.lbl_cutout_x,
                prefs.getInt(Prefs.CUTOUT_OFF_X, 0) + 100, R.string.lbl_cutout_x,
                p -> prefs.edit().putInt(Prefs.CUTOUT_OFF_X, p - 100).apply());
        bindSlider(R.id.sb_cutout_y, R.id.lbl_cutout_y,
                prefs.getInt(Prefs.CUTOUT_OFF_Y, 0) + 100, R.string.lbl_cutout_y,
                p -> prefs.edit().putInt(Prefs.CUTOUT_OFF_Y, p - 100).apply());
        bindSlider(R.id.sb_cutout_scale, R.id.lbl_cutout_scale,
                prefs.getInt(Prefs.CUTOUT_SCALE, 100) - 50, R.string.lbl_cutout_scale,
                p -> prefs.edit().putInt(Prefs.CUTOUT_SCALE, p + 50).apply());
    }

    private void bindTextInputs()
    {
        EditText on = findViewById(R.id.et_interval_on);
        EditText off = findViewById(R.id.et_interval_off);
        on.setText(String.valueOf(prefs.getInt(Prefs.INTERVAL_ON_SEC, 4)));
        off.setText(String.valueOf(prefs.getInt(Prefs.INTERVAL_OFF_SEC, 6)));
        on.setOnFocusChangeListener((v, has) -> { if (!has) { putIntFrom(on, Prefs.INTERVAL_ON_SEC, 4, 1, 120); } });
        off.setOnFocusChangeListener((v, has) -> { if (!has) { putIntFrom(off, Prefs.INTERVAL_OFF_SEC, 6, 1, 120); } });

        EditText ns = findViewById(R.id.et_night_start);
        EditText ne = findViewById(R.id.et_night_end);
        ns.setText(minutesToHhMm(prefs.getInt(Prefs.NIGHT_START, 23 * 60)));
        ne.setText(minutesToHhMm(prefs.getInt(Prefs.NIGHT_END, 7 * 60)));
        ns.setOnFocusChangeListener((v, has) -> { if (!has) { putMinutesFrom(ns, Prefs.NIGHT_START, 23 * 60); } });
        ne.setOnFocusChangeListener((v, has) -> { if (!has) { putMinutesFrom(ne, Prefs.NIGHT_END, 7 * 60); } });
    }

    private void putIntFrom(EditText et, String key, int def, int min, int max)
    {
        try
        {
            int v = Integer.parseInt(et.getText().toString().trim());
            v = Math.max(min, Math.min(max, v));
            prefs.edit().putInt(key, v).apply();
            et.setText(String.valueOf(v));
        }
        catch (Exception e) { et.setText(String.valueOf(def)); }
    }

    private void putMinutesFrom(EditText et, String key, int def)
    {
        try
        {
            String[] parts = et.getText().toString().trim().split(":");
            int h = Integer.parseInt(parts[0]);
            int m = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int v = Math.max(0, Math.min(1439, h * 60 + m));
            prefs.edit().putInt(key, v).apply();
            sendLocal(notificationobserver.ACTION_REEVAL);
            et.setText(minutesToHhMm(v));
        }
        catch (Exception e) { et.setText(minutesToHhMm(def)); }
    }

    private static String minutesToHhMm(int minutes)
    {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private void bindButtons()
    {
        findViewById(R.id.btn_test).setOnClickListener(v -> {
            Intent i = new Intent(notificationobserver.ACTION_TEST);
            i.setPackage(getPackageName());
            i.putExtra("color", Prefs.color(this));
            sendBroadcast(i);
        });
        findViewById(R.id.btn_hide).setOnClickListener(v -> sendLocal(notificationobserver.ACTION_HIDE));
        findViewById(R.id.btn_select_apps).setOnClickListener(v ->
                startActivity(new Intent(this, AppPickerActivity.class)));
        Button logBtn = findViewById(R.id.btn_toggle_log);
        logBtn.setOnClickListener(v -> {
            boolean show = debug_text.getVisibility() != View.VISIBLE;
            debug_text.setVisibility(show ? View.VISIBLE : View.GONE);
            logBtn.setText(show ? R.string.log_hide : R.string.log_show);
        });
        findViewById(R.id.btn_detect_cutout).setOnClickListener(v -> {
            try
            {
                CutoutLocator.reportInsets(this, getWindow().getDecorView().getRootWindowInsets());
                boolean has = prefs.getInt(Prefs.CUTOUT_LEFT, -1) >= 0;
                Toast.makeText(this, has ? R.string.cutout_detected : R.string.cutout_none,
                        Toast.LENGTH_SHORT).show();
            }
            catch (Exception e)
            {
                Toast.makeText(this, R.string.cutout_none, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---- per-app colors ------------------------------------------------------

    @Override
    public void onItemLongClick(String pkg)
    {
        String label = pkg;
        try
        {
            label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        }
        catch (Exception ignored) {}

        ColorDialogs.showAppColor(this, pkg, label,
                () -> sendLocal(notificationobserver.ACTION_REEVAL));
    }

    // ---- live items -----------------------------------------------------------------

    private class items_receiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            ArrayList<NotificationItem> list =
                    intent.getParcelableArrayListExtra(notificationobserver.EXTRA_ITEMS);
            refreshItems(list);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void refreshItems(List<NotificationItem> list)
    {
        CustomListAdapter.logo_list.clear();
        if (list != null)
        {
            for (NotificationItem item : list)
            {
                if (!item.batteryEvent)
                {
                    CustomListAdapter.logo_list.add(new CustomListAdapter.noti_logo_item(item.pkg, item.key));
                }
            }
            append_dbg("items: " + list.size() + "\n");
        }
        if (adapter != null) { adapter.notifyDataSetChanged(); }
    }

    // ---- misc ----------------------------------------------------------------------------

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

    @Override
    protected void onResume()
    {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onDestroy()
    {
        try { unregisterReceiver(itemsReceiver); } catch (Exception ignored) {}
        debug_text = null;
        main_handler_s = null;
        super.onDestroy();
    }

    static String long_date_time_format_or_empty(long timestamp_in_millis)
    {
        try { return df_date_time_long.format(new Date(timestamp_in_millis)); }
        catch (Exception e) { return ""; }
    }

    public static void append_dbg(final String text)
    {
        Handler h = main_handler_s;
        if (h == null) { return; }
        h.post(() -> {
            try
            {
                TextView tv = debug_text;
                if (tv == null) { return; }
                if (tv.getText().length() > 20000) { tv.setText(""); }
                tv.append(long_date_time_format_or_empty(System.currentTimeMillis()) + " " + text);
            }
            catch (Exception e)
            {
                Log.w(TAG, "append_dbg: " + e);
            }
        });
    }
}
