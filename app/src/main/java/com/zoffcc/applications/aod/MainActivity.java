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
    private static final String TAG = Dbg.TAG_PREFIX + "Main";

    @SuppressLint("StaticFieldLeak")
    public static TextView debug_text = null;
    static Handler main_handler_s = null;
    @SuppressLint("SimpleDateFormat")
    final static SimpleDateFormat df_date_time_long = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    static CustomListAdapter adapter = null;

    private final Handler main_handler = new Handler(Looper.getMainLooper());
    private static final int REQ_EXPORT_DIR = 4711;
    private static final int REQ_IMPORT_FILE = 4712;
    private static final String IMPORT_TMP = "aod-import.json";

    private SharedPreferences prefs;
    private DocumentExporter document_exporter;
    private DocumentImporter document_importer;
    private TextView status_text;
    private items_receiver itemsReceiver;
    private android.database.ContentObserver brightnessObserver;

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
        // The notification-text preview is gone for good: this app never
        // reads titles or message bodies. Drop the flag old installs kept.
        prefs.edit().remove("preview").apply();

        bindSwitches();
        bindBrightnessRow();
        bindSpinners();
        bindPersistentRow();
        bindColorRow();
        bindSliders();
        bindTextInputs();
        bindButtons();
        bindBackupButtons();
        // sections and search last: the search index reads the labels and the
        // spinner choices, so every control has to be populated first
        SettingsSections.install(this, prefs);

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
        // After an update the service is often off: put it back silently when
        // we are allowed to, then show the real state below.
        if (isAccessibilityEnabled()) { Prefs.markAccessSeen(this); }
        else { AccessRestore.restore(this, "appStart"); }

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

    private void bindSwitch(int id, String key)
    {
        boolean def = SettingsSchema.defBool(key);
        SwitchCompat sw = findViewById(id);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(key, checked).apply());
    }

    private void bindSwitches()
    {
        bindSwitch(R.id.sw_enabled, Prefs.ENABLED);
        bindSwitch(R.id.sw_aod_light, Prefs.AOD_LIGHT);
        bindSwitch(R.id.sw_screen_on_light, Prefs.SCREEN_ON_LIGHT);
        bindSwitch(R.id.sw_double_tap, Prefs.DOUBLE_TAP_WAKE);
        bindSwitch(R.id.sw_clear_unlock, Prefs.CLEAR_ON_UNLOCK);
        bindSwitch(R.id.sw_relight_relock, Prefs.RELIGHT_RELOCK);
        bindSwitch(R.id.sw_canvas_fallback, Prefs.CANVAS_FALLBACK);
        bindSwitch(R.id.sw_blackout_full, Prefs.BLACKOUT_FULL);
        bindSwitch(R.id.sw_icon_plate, Prefs.ICON_PLATE);
        bindSwitch(R.id.sw_icon_shift, Prefs.ICON_SHIFT);
        bindSwitch(R.id.sw_icon_center_h, Prefs.ICON_CENTER_H);
        bindSwitch(R.id.sw_icon_last_row, Prefs.ICON_LAST_ROW_CENTER);
        bindSwitch(R.id.sw_icon_clip, Prefs.ICON_CLIP);
        bindSwitch(R.id.sw_icon_count, Prefs.ICON_COUNT);
        bindSwitch(R.id.sw_icon_count_always, Prefs.ICON_COUNT_ALWAYS);
        bindSwitch(R.id.sw_icon_count_badge, Prefs.ICON_COUNT_BADGE);
        bindSwitch(R.id.sw_icon_count_add, Prefs.ICON_COUNT_ADD);
        bindSwitch(R.id.sw_auto_color, Prefs.AUTO_COLOR);
        bindSwitch(R.id.sw_eco, Prefs.ECO);
        bindSwitch(R.id.sw_interval, Prefs.INTERVAL);
        bindSwitch(R.id.sw_night, Prefs.NIGHT);
        bindSwitch(R.id.sw_dnd, Prefs.RESPECT_DND);
        bindSwitch(R.id.sw_battery_light, Prefs.BATTERY_LIGHT);
    }

    /**
     * The brightness slider means two different things depending on the
     * switch, so it is bound here rather than with the plain sliders.
     */
    private void bindBrightnessRow()
    {
        SeekBar sb = findViewById(R.id.sb_brightness);
        sb.setProgress(prefs.getInt(Prefs.BRIGHTNESS, SettingsSchema.defInt(Prefs.BRIGHTNESS)) - 5);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                updateBrightnessRows();
                if (fromUser) { prefs.edit().putInt(Prefs.BRIGHTNESS, progress + 5).apply(); }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        SwitchCompat awake = findViewById(R.id.sw_awake_panel);
        awake.setChecked(Prefs.awakePanel(this));
        awake.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.AWAKE_PANEL, checked).apply();
            updateBrightnessRows();
        });

        SwitchCompat sw = findViewById(R.id.sw_follow_brightness);
        sw.setChecked(Prefs.followBrightness(this));
        sw.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.FOLLOW_BRIGHTNESS, checked).apply();
            updateBrightnessRows();
        });
        updateBrightnessRows();
    }

    /**
     * Three possible states, and the rows have to say which one is active:
     * awake + follow  -> the phone's brightness, slider live, no own value;
     * awake + own     -> an own brightness on an awake panel;
     * dozing          -> an own brightness on a dozing panel, following is
     *                    not possible there at all.
     */
    private void updateBrightnessRows()
    {
        boolean awake = Prefs.awakePanel(this);
        boolean system = Brightness.usesSystem(this);
        SwitchCompat sw = findViewById(R.id.sw_follow_brightness);
        sw.setEnabled(awake);
        sw.setAlpha(awake ? 1f : 0.55f);

        SeekBar sb = findViewById(R.id.sb_brightness);
        TextView lbl = findViewById(R.id.lbl_brightness);
        TextView note = findViewById(R.id.tv_brightness_auto);
        // An own percentage cannot be combined with "let the phone decide":
        // any request of ours would take the panel off the system slider.
        sb.setEnabled(!system);
        sb.setAlpha(system ? 0.5f : 1f);
        lbl.setAlpha(system ? 0.6f : 1f);
        lbl.setText(system ? getString(R.string.lbl_brightness_system)
                : getString(R.string.lbl_brightness, sb.getProgress() + 5));
        note.setVisibility(View.VISIBLE);
        if (system)
        {
            note.setText(Brightness.auto(this)
                    ? R.string.brightness_adaptive_on : R.string.brightness_adaptive_off);
        }
        else
        {
            note.setText(awake ? R.string.brightness_own_note : R.string.brightness_doze_note);
        }
    }

    private void bindSpinners()
    {
        Spinner style = findViewById(R.id.sp_style);
        ArrayAdapter<CharSequence> sa = ArrayAdapter.createFromResource(this,
                R.array.style_entries, R.layout.item_spinner);
        sa.setDropDownViewResource(R.layout.item_spinner_dropdown);
        style.setAdapter(sa);
        String[] styleVals = {Prefs.STYLE_RING, Prefs.STYLE_EDGE, Prefs.STYLE_DOT, Prefs.STYLE_ICONS};
        style.setSelection(indexOf(styleVals, prefs.getString(Prefs.STYLE, SettingsSchema.defString(Prefs.STYLE))));
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
        anim.setSelection(indexOf(animVals, prefs.getString(Prefs.ANIM, SettingsSchema.defString(Prefs.ANIM))));
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
        tint.setSelection(indexOf(tintVals, prefs.getString(Prefs.ICON_TINT, SettingsSchema.defString(Prefs.ICON_TINT))));
        tint.setOnItemSelectedListener(new SimpleItemSelected()
        {
            @Override
            public void onItemSelected(int position)
            {
                prefs.edit().putString(Prefs.ICON_TINT, tintVals[position]).apply();
            }
        });

        Spinner iconSrc = findViewById(R.id.sp_icon_source);
        ArrayAdapter<CharSequence> isa = ArrayAdapter.createFromResource(this,
                R.array.icon_source_entries, R.layout.item_spinner);
        isa.setDropDownViewResource(R.layout.item_spinner_dropdown);
        iconSrc.setAdapter(isa);
        String[] srcVals = {Prefs.ICON_SRC_APP, Prefs.ICON_SRC_NOTIFICATION};
        iconSrc.setSelection(indexOf(srcVals, Prefs.iconSource(this)));
        iconSrc.setOnItemSelectedListener(new SimpleItemSelected()
        {
            @Override
            public void onItemSelected(int position)
            {
                prefs.edit().putString(Prefs.ICON_SOURCE, srcVals[position]).apply();
            }
        });

        Spinner countSrc = findViewById(R.id.sp_icon_count_source);
        ArrayAdapter<CharSequence> ca = ArrayAdapter.createFromResource(this,
                R.array.icon_count_source_entries, R.layout.item_spinner);
        ca.setDropDownViewResource(R.layout.item_spinner_dropdown);
        countSrc.setAdapter(ca);
        String[] countVals = {Prefs.ICON_COUNT_SRC_NOTIFICATIONS, Prefs.ICON_COUNT_SRC_APP};
        countSrc.setSelection(indexOf(countVals, Prefs.iconCountSource(this)));
        countSrc.setOnItemSelectedListener(new SimpleItemSelected()
        {
            @Override
            public void onItemSelected(int position)
            {
                prefs.edit().putString(Prefs.ICON_COUNT_SOURCE, countVals[position]).apply();
            }
        });

        Spinner anchor = findViewById(R.id.sp_icon_y_anchor);
        ArrayAdapter<CharSequence> ya = ArrayAdapter.createFromResource(this,
                R.array.icon_y_anchor_entries, R.layout.item_spinner);
        ya.setDropDownViewResource(R.layout.item_spinner_dropdown);
        anchor.setAdapter(ya);
        String[] anchorVals = {Prefs.ICON_Y_CAMERA, Prefs.ICON_Y_TOP, Prefs.ICON_Y_BOTTOM};
        anchor.setSelection(indexOf(anchorVals, prefs.getString(Prefs.ICON_Y_ANCHOR, SettingsSchema.defString(Prefs.ICON_Y_ANCHOR))));
        anchor.setOnItemSelectedListener(new SimpleItemSelected()
        {
            @Override
            public void onItemSelected(int position)
            {
                prefs.edit().putString(Prefs.ICON_Y_ANCHOR, anchorVals[position]).apply();
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

    /**
     * Persistent notifications: one choice instead of the old on/off switch,
     * plus the always-allow app list, whose button only makes sense in the
     * "calls + selected apps" mode.
     */
    private void bindPersistentRow()
    {
        Spinner sp = findViewById(R.id.sp_persistent_mode);
        ArrayAdapter<CharSequence> pa = ArrayAdapter.createFromResource(this,
                R.array.persistent_mode_entries, R.layout.item_spinner);
        pa.setDropDownViewResource(R.layout.item_spinner_dropdown);
        sp.setAdapter(pa);

        Button apps = findViewById(R.id.btn_persistent_apps);
        apps.setOnClickListener(v -> {
            Intent i = new Intent(this, AppPickerActivity.class);
            i.putExtra(AppPickerActivity.EXTRA_MODE, AppPickerActivity.MODE_PERSISTENT);
            startActivity(i);
        });

        String mode = Prefs.persistentMode(this);
        sp.setSelection(PersistentPolicy.indexOf(mode));
        apps.setVisibility(PersistentPolicy.usesAppList(mode) ? View.VISIBLE : View.GONE);
        sp.setOnItemSelectedListener(new SimpleItemSelected()
        {
            @Override
            public void onItemSelected(int position)
            {
                String next = PersistentPolicy.MODES[position];
                prefs.edit().putString(Prefs.PERSISTENT_MODE, next).apply();
                apps.setVisibility(PersistentPolicy.usesAppList(next) ? View.VISIBLE : View.GONE);
                sendLocal(notificationobserver.ACTION_REEVAL);
            }
        });
    }

    private void bindColorRow()
    {
        LinearLayout row = findViewById(R.id.color_row);
        int size = dp(40);
        int margin = dp(5);
        int current = prefs.getInt(Prefs.COLOR, SettingsSchema.defInt(Prefs.COLOR));
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

    /**
     * Maps the raw seek bar position to the number the label shows. The
     * position often starts at 0 while the stored value starts higher
     * (icon size at 18 dp, the animation cycle at 300 ms), so the label
     * must apply the same shift -- and for the cycle also the scale --
     * as the writer, or a fresh install reads "0 dp".
     */
    private interface SliderLabel { int shown(int progress); }

    private void bindSlider(int id, int labelId, int initial, int formatRes, SliderWriter writer)
    {
        bindSlider(id, labelId, initial, formatRes, p -> p, writer);
    }

    private void bindSlider(int id, int labelId, int initial, int formatRes,
                            SliderLabel label, SliderWriter writer)
    {
        SeekBar sb = findViewById(id);
        TextView lbl = findViewById(labelId);
        sb.setProgress(initial);
        lbl.setText(getString(formatRes, label.shown(initial)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                lbl.setText(getString(formatRes, label.shown(progress)));
                if (fromUser) { writer.write(progress); }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    /**
     * Light duration: a stepped slider. The seek bar position is the index
     * into LightDuration.STEPS_SEC, so every position is one of the twelve
     * useful values and the thumb always lands on a tick mark.
     */
    private void bindTimeoutRow()
    {
        SeekBar sb = findViewById(R.id.sb_timeout);
        TextView lbl = findViewById(R.id.lbl_timeout);
        sb.setMax(LightDuration.count() - 1);

        int stored = Prefs.timeoutSec(this);
        int index = LightDuration.nearestIndex(stored);
        if (LightDuration.seconds(index) != stored)
        {
            // an off-grid value from an older version: settle on the nearest step
            prefs.edit().putInt(Prefs.TIMEOUT_SEC, LightDuration.seconds(index)).apply();
        }
        sb.setProgress(index);
        lbl.setText(getString(R.string.lbl_timeout, LightDuration.label(this, index)));
        ((TextView) findViewById(R.id.lbl_timeout_min)).setText(LightDuration.label(this, 0));
        ((TextView) findViewById(R.id.lbl_timeout_max))
                .setText(LightDuration.label(this, LightDuration.count() - 1));

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                lbl.setText(getString(R.string.lbl_timeout, LightDuration.label(MainActivity.this, progress)));
                if (fromUser)
                {
                    prefs.edit().putInt(Prefs.TIMEOUT_SEC, LightDuration.seconds(progress)).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void bindSliders()
    {
        bindTimeoutRow();
        bindSlider(R.id.sb_stroke, R.id.lbl_stroke,
                prefs.getInt(Prefs.STROKE_DP, SettingsSchema.defInt(Prefs.STROKE_DP)) - 2, R.string.lbl_stroke,
                p -> p + 2,
                p -> prefs.edit().putInt(Prefs.STROKE_DP, p + 2).apply());
        bindSlider(R.id.sb_icon_size, R.id.lbl_icon_size,
                prefs.getInt(Prefs.ICON_SIZE_DP, SettingsSchema.defInt(Prefs.ICON_SIZE_DP)) - 18, R.string.lbl_icon_size,
                p -> p + 18,
                p -> prefs.edit().putInt(Prefs.ICON_SIZE_DP, p + 18).apply());
        bindSlider(R.id.sb_icon_offset_x, R.id.lbl_icon_offset_x,
                prefs.getInt(Prefs.ICON_OFFSET_X_DP, SettingsSchema.defInt(Prefs.ICON_OFFSET_X_DP)) + 200, R.string.lbl_icon_offset_x,
                p -> p - 200,
                p -> prefs.edit().putInt(Prefs.ICON_OFFSET_X_DP, p - 200).apply());
        bindSlider(R.id.sb_icon_offset_y, R.id.lbl_icon_offset_y,
                prefs.getInt(Prefs.ICON_OFFSET_Y_DP, SettingsSchema.defInt(Prefs.ICON_OFFSET_Y_DP)), R.string.lbl_icon_offset_y,
                p -> prefs.edit().putInt(Prefs.ICON_OFFSET_Y_DP, p).apply());
        bindSlider(R.id.sb_icon_per_row, R.id.lbl_icon_per_row,
                prefs.getInt(Prefs.ICON_PER_ROW, SettingsSchema.defInt(Prefs.ICON_PER_ROW)), R.string.lbl_icon_per_row,
                p -> prefs.edit().putInt(Prefs.ICON_PER_ROW, p).apply());
        bindSlider(R.id.sb_icon_gap, R.id.lbl_icon_gap,
                prefs.getInt(Prefs.ICON_GAP_DP, SettingsSchema.defInt(Prefs.ICON_GAP_DP)), R.string.lbl_icon_gap,
                p -> prefs.edit().putInt(Prefs.ICON_GAP_DP, p).apply());
        bindSlider(R.id.sb_icon_row_gap, R.id.lbl_icon_row_gap,
                prefs.getInt(Prefs.ICON_ROW_GAP_DP, SettingsSchema.defInt(Prefs.ICON_ROW_GAP_DP)), R.string.lbl_icon_row_gap,
                p -> prefs.edit().putInt(Prefs.ICON_ROW_GAP_DP, p).apply());
        bindSlider(R.id.sb_icon_max, R.id.lbl_icon_max,
                prefs.getInt(Prefs.ICON_MAX, SettingsSchema.defInt(Prefs.ICON_MAX)) - 1, R.string.lbl_icon_max,
                p -> p + 1,
                p -> prefs.edit().putInt(Prefs.ICON_MAX, p + 1).apply());
        bindSlider(R.id.sb_icon_count_size, R.id.lbl_icon_count_size,
                prefs.getInt(Prefs.ICON_COUNT_SIZE_DP, SettingsSchema.defInt(Prefs.ICON_COUNT_SIZE_DP)) - 10, R.string.lbl_icon_count_size,
                p -> p + 10,
                p -> prefs.edit().putInt(Prefs.ICON_COUNT_SIZE_DP, p + 10).apply());
        bindSlider(R.id.sb_speed, R.id.lbl_speed,
                (prefs.getInt(Prefs.SPEED_MS, SettingsSchema.defInt(Prefs.SPEED_MS)) - 300) / 100, R.string.lbl_speed,
                p -> 300 + p * 100,
                p -> prefs.edit().putInt(Prefs.SPEED_MS, 300 + p * 100).apply());
        bindSlider(R.id.sb_cutout_x, R.id.lbl_cutout_x,
                prefs.getInt(Prefs.CUTOUT_OFF_X, SettingsSchema.defInt(Prefs.CUTOUT_OFF_X)) + 100, R.string.lbl_cutout_x,
                p -> p - 100,
                p -> prefs.edit().putInt(Prefs.CUTOUT_OFF_X, p - 100).apply());
        bindSlider(R.id.sb_cutout_y, R.id.lbl_cutout_y,
                prefs.getInt(Prefs.CUTOUT_OFF_Y, SettingsSchema.defInt(Prefs.CUTOUT_OFF_Y)) + 100, R.string.lbl_cutout_y,
                p -> p - 100,
                p -> prefs.edit().putInt(Prefs.CUTOUT_OFF_Y, p - 100).apply());
        bindSlider(R.id.sb_cutout_scale, R.id.lbl_cutout_scale,
                prefs.getInt(Prefs.CUTOUT_SCALE, SettingsSchema.defInt(Prefs.CUTOUT_SCALE)) - 50, R.string.lbl_cutout_scale,
                p -> p + 50,
                p -> prefs.edit().putInt(Prefs.CUTOUT_SCALE, p + 50).apply());
    }

    private void bindTextInputs()
    {
        EditText on = findViewById(R.id.et_interval_on);
        EditText off = findViewById(R.id.et_interval_off);
        on.setText(String.valueOf(prefs.getInt(Prefs.INTERVAL_ON_SEC, SettingsSchema.defInt(Prefs.INTERVAL_ON_SEC))));
        off.setText(String.valueOf(prefs.getInt(Prefs.INTERVAL_OFF_SEC, SettingsSchema.defInt(Prefs.INTERVAL_OFF_SEC))));
        on.setOnFocusChangeListener((v, has) -> { if (!has) { putIntFrom(on, Prefs.INTERVAL_ON_SEC, 4, 1, 120); } });
        off.setOnFocusChangeListener((v, has) -> { if (!has) { putIntFrom(off, Prefs.INTERVAL_OFF_SEC, 6, 1, 120); } });

        EditText ns = findViewById(R.id.et_night_start);
        EditText ne = findViewById(R.id.et_night_end);
        ns.setText(minutesToHhMm(prefs.getInt(Prefs.NIGHT_START, SettingsSchema.defInt(Prefs.NIGHT_START))));
        ne.setText(minutesToHhMm(prefs.getInt(Prefs.NIGHT_END, SettingsSchema.defInt(Prefs.NIGHT_END))));
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

    // ---- settings backup ---------------------------------------------------

    /**
     * Export and import go through the same Storage Access Framework helpers
     * the other apps of this project use: the exporter remembers the folder
     * you picked once, the importer copies the picked file into the cache so
     * the read happens off the UI thread.
     */
    private void bindBackupButtons()
    {
        document_exporter = new DocumentExporter(this, "aod_settings_dir", REQ_EXPORT_DIR);
        document_importer = new DocumentImporter(this, REQ_IMPORT_FILE);

        findViewById(R.id.btn_export_settings).setOnClickListener(v ->
                document_exporter.ensureDirectory(this::exportSettings,
                        err -> toast(getString(R.string.export_failed, err))));

        // the folder is remembered; long press picks a different one
        findViewById(R.id.btn_export_settings).setOnLongClickListener(v -> {
            document_exporter.changeDirectory(this::exportSettings,
                    err -> toast(getString(R.string.export_failed, err)));
            return true;
        });

        findViewById(R.id.btn_import_settings).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle(R.string.import_confirm_title)
                        .setMessage(R.string.import_confirm_text)
                        .setPositiveButton(R.string.yes, (d, w) -> startImport())
                        .setNegativeButton(R.string.no, null)
                        .show());
    }

    private void startImport()
    {
        document_importer.importFile(getCacheDir().getAbsolutePath(), IMPORT_TMP, null,
                new DocumentImporter.ImportCallback()
                {
                    @Override
                    public void onSuccess() { applyImportedFile(); }

                    @Override
                    public void onError(String message)
                    {
                        toast(getString(R.string.import_failed, message));
                    }
                });
    }

    /** The picked file is in the cache now: read it, apply it, refresh the UI. */
    private void applyImportedFile()
    {
        java.io.File file = new java.io.File(getCacheDir(), IMPORT_TMP);
        String text;
        try
        {
            text = SettingsIo.readUtf8(file);
        }
        catch (Exception e)
        {
            String why = "file too large".equals(e.getMessage())
                    ? getString(R.string.import_too_large) : String.valueOf(e.getMessage());
            toast(getString(R.string.import_failed, why));
            return;
        }
        finally
        {
            try { file.delete(); } catch (Exception ignored) {}
        }
        SettingsIo.Result r = SettingsIo.apply(this, text);
        if (!r.ok())
        {
            toast(getString(R.string.import_failed, r.error));
            return;
        }
        toast(getString(R.string.import_done, r.applied, r.skipped, r.unknown));
        sendLocal(notificationobserver.ACTION_REEVAL);
        recreate();
    }

    /** Runs on a worker thread: SAF file creation and writing are slow. */
    private void exportSettings()
    {
        new Thread(() -> {
            String text;
            try
            {
                text = SettingsIo.export(this);
            }
            catch (Exception e)
            {
                postToast(getString(R.string.export_failed, String.valueOf(e)));
                return;
            }
            Uri uri = document_exporter.createFileUri(SettingsIo.FILE_NAME, SettingsIo.MIME);
            if (uri == null)
            {
                postToast(getString(R.string.export_failed, "no file created"));
                return;
            }
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "w"))
            {
                out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                Dbg.log("settings", "exported " + text.length() + " chars");
                postToast(getString(R.string.export_ok));
            }
            catch (Exception e)
            {
                postToast(getString(R.string.export_failed, String.valueOf(e)));
            }
        }).start();
    }

    private void toast(String message)
    {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void postToast(String message)
    {
        main_handler.post(() -> toast(message));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        if (document_exporter != null
                && document_exporter.handleActivityResult(requestCode, resultCode, data)) { return; }
        if (document_importer != null)
        {
            document_importer.handleActivityResult(requestCode, resultCode, data);
        }
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
        findViewById(R.id.btn_icon_reset).setOnClickListener(v -> {
            Prefs.resetIconLayout(this);
            Dbg.log("ui", "icon position reset to defaults");
            Toast.makeText(this, R.string.icon_pos_reset, Toast.LENGTH_SHORT).show();
            recreate(); // refresh every slider, switch and spinner from the store
        });
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
                boolean has = prefs.getInt(Prefs.CUTOUT_LEFT, SettingsSchema.defInt(Prefs.CUTOUT_LEFT)) >= 0;
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
        updateBrightnessRows();
        if (brightnessObserver == null)
        {
            brightnessObserver = Brightness.watch(this, this::updateBrightnessRows);
        }
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        Brightness.unwatch(this, brightnessObserver);
        brightnessObserver = null;
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
