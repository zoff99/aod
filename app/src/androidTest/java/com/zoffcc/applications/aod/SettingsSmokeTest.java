package com.zoffcc.applications.aod;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import android.content.pm.ServiceInfo;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Device smoke test: the settings screen builds, its sections fold, the
 * search finds a setting, the duration slider is stepped, and the two
 * services the app depends on are declared. Run with ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4.class)
public class SettingsSmokeTest
{
    private Context target()
    {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void packageNameIsTheOneWeExpect()
    {
        assertEquals("com.zoffcc.applications.aod", target().getPackageName());
    }

    @Test
    public void settingsScreenHasItsControls()
    {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class))
        {
            s.onActivity(a -> {
                assertNotNull("awake panel switch missing", a.findViewById(R.id.sw_awake_panel));
                assertNotNull("icon size slider missing", a.findViewById(R.id.sb_icon_size));
                assertNotNull("app picker button missing", a.findViewById(R.id.btn_select_apps));
                assertNotNull("persistent policy spinner missing", a.findViewById(R.id.sp_persistent_mode));
                assertNotNull("always-allow picker button missing", a.findViewById(R.id.btn_persistent_apps));
                assertNotNull("export button missing", a.findViewById(R.id.btn_export_settings));
                assertNotNull("import button missing", a.findViewById(R.id.btn_import_settings));
                assertNotNull("clock mode spinner missing", a.findViewById(R.id.sp_clock_mode));
            });
        }
    }

    @Test
    public void bothServicesAreDeclared() throws Exception
    {
        Context c = target();
        ServiceInfo listener = c.getPackageManager().getServiceInfo(
                new ComponentName(c, notificationobserver.class), 0);
        assertTrue("notification listener must not be exported", !listener.exported);

        ServiceInfo overlay = c.getPackageManager().getServiceInfo(
                new ComponentName(c, overlayservice.class), 0);
        assertNotNull(overlay);
    }

    @Test
    public void sectionsFoldAndSearchFindsSettings()
    {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class))
        {
            s.onActivity(a -> {
                View basics = a.findViewById(R.id.body_basics);
                assertNotNull("basics section missing", basics);
                int before = basics.getVisibility();
                a.findViewById(R.id.hdr_basics).performClick();
                assertTrue("tapping a header must fold or unfold its section",
                        basics.getVisibility() != before);

                EditText search = a.findViewById(R.id.et_settings_search);
                assertNotNull("settings search field missing", search);
                search.setText("brightness");
                assertEquals("search must open the section that matches",
                        View.VISIBLE, a.findViewById(R.id.body_time).getVisibility());
                assertEquals("the matching setting must be on screen",
                        View.VISIBLE, a.findViewById(R.id.sw_follow_brightness).getVisibility());
                assertEquals("a section without a match must be hidden",
                        View.GONE, a.findViewById(R.id.hdr_cutout).getVisibility());

                search.setText("");
                assertEquals("clearing the search must bring every section back",
                        View.VISIBLE, a.findViewById(R.id.hdr_cutout).getVisibility());
            });
        }
    }

    @Test
    public void lightDurationIsOneOfTheSteps()
    {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class))
        {
            s.onActivity(a -> {
                SeekBar sb = a.findViewById(R.id.sb_timeout);
                assertEquals("the slider must have one position per step",
                        LightDuration.count() - 1, sb.getMax());
                int stored = Prefs.timeoutSec(a);
                boolean isStep = false;
                for (int step : LightDuration.STEPS_SEC) { if (step == stored) { isStep = true; } }
                assertTrue("stored duration " + stored + " is not a step", isStep);
                TextView lbl = a.findViewById(R.id.lbl_timeout);
                String text = lbl.getText().toString();
                assertTrue("duration must be labelled in min or h, not seconds: " + text,
                        text.contains("min") || text.contains(" h"));
            });
        }
    }

    @Test
    public void iconSizeIsOneOfTheSteps()
    {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class))
        {
            s.onActivity(a -> {
                SeekBar sb = a.findViewById(R.id.sb_icon_size);
                assertEquals("the slider must have one position per step",
                        IconSize.count() - 1, sb.getMax());
                int stored = Prefs.iconSizeDp(a);
                boolean isStep = false;
                for (int step : IconSize.STEPS_DP) { if (step == stored) { isStep = true; } }
                assertTrue("stored icon size " + stored + " is not a step", isStep);
                TextView lbl = a.findViewById(R.id.lbl_icon_size);
                assertTrue("icon size must be labelled in dp: " + lbl.getText(),
                        lbl.getText().toString().contains("dp"));
            });
        }
    }

    @Test
    public void freshInstallLabelsShowTheRealDefaults()
    {
        // regression: the icon size label once showed the raw seek bar
        // position ("0 dp") instead of the stored default ("40 dp")
        SharedPreferences p = Prefs.get(target());
        Map<String, ?> saved = new HashMap<>(p.getAll());
        try
        {
            p.edit().clear().commit();
            try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class))
            {
                s.onActivity(a -> {
                    assertEquals("icon size label must show the default, not the slider position",
                            a.getString(R.string.lbl_icon_size, SettingsSchema.defInt(Prefs.ICON_SIZE_DP)),
                            ((TextView) a.findViewById(R.id.lbl_icon_size)).getText().toString());
                    assertEquals(a.getString(R.string.lbl_clock_time_size, SettingsSchema.defInt(Prefs.CLOCK_TIME_SIZE_DP)),
                            ((TextView) a.findViewById(R.id.lbl_clock_time_size)).getText().toString());
                    assertEquals(a.getString(R.string.lbl_stroke, SettingsSchema.defInt(Prefs.STROKE_DP)),
                            ((TextView) a.findViewById(R.id.lbl_stroke)).getText().toString());
                    assertEquals(a.getString(R.string.lbl_speed, SettingsSchema.defInt(Prefs.SPEED_MS)),
                            ((TextView) a.findViewById(R.id.lbl_speed)).getText().toString());
                    assertEquals(a.getString(R.string.lbl_cutout_scale, SettingsSchema.defInt(Prefs.CUTOUT_SCALE)),
                            ((TextView) a.findViewById(R.id.lbl_cutout_scale)).getText().toString());
                    assertEquals(a.getString(R.string.lbl_cutout_x, SettingsSchema.defInt(Prefs.CUTOUT_OFF_X)),
                            ((TextView) a.findViewById(R.id.lbl_cutout_x)).getText().toString());
                    assertEquals(a.getString(R.string.lbl_brightness, SettingsSchema.defInt(Prefs.BRIGHTNESS)),
                            ((TextView) a.findViewById(R.id.lbl_brightness)).getText().toString());
                    int index = LightDuration.nearestIndex(SettingsSchema.defInt(Prefs.TIMEOUT_SEC));
                    assertEquals(a.getString(R.string.lbl_timeout, LightDuration.label(a, index)),
                            ((TextView) a.findViewById(R.id.lbl_timeout)).getText().toString());
                });
            }
        }
        finally
        {
            restore(p, saved);
        }
    }

    @Test
    public void alwaysAllowPickerOpensWithItsOwnTitle()
    {
        Intent i = new Intent(target(), AppPickerActivity.class);
        i.putExtra(AppPickerActivity.EXTRA_MODE, AppPickerActivity.MODE_PERSISTENT);
        try (ActivityScenario<AppPickerActivity> s = ActivityScenario.launch(i))
        {
            s.onActivity(a -> {
                TextView title = a.findViewById(R.id.txt_title);
                assertEquals("the persistent picker must say what it selects",
                        a.getString(R.string.picker_persistent_title), title.getText().toString());
            });
        }
    }

    @Test
    public void callsSurviveTheDefaultPersistentPolicy()
    {
        // the default must not swallow a call, and must still hide a player
        String mode = Prefs.persistentMode(target());
        assertEquals(PersistentPolicy.CALLS_APPS, mode);
        assertTrue(PersistentPolicy.allow(mode, true, false));
        assertFalse(PersistentPolicy.allow(mode, false, false));
    }

    @Test
    public void settingsBackupRoundTrips()
    {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class))
        {
            s.onActivity(a -> {
                SharedPreferences p = Prefs.get(a);
                int oldTimeout = Prefs.timeoutSec(a);
                String oldStyle = p.getString(Prefs.STYLE, Prefs.STYLE_ICONS);
                Set<String> oldApps = new HashSet<>(p.getStringSet(Prefs.FILTER_APPS, new HashSet<>()));
                try
                {
                    p.edit().putInt(Prefs.TIMEOUT_SEC, 900)
                            .putString(Prefs.STYLE, Prefs.STYLE_EDGE)
                            .putStringSet(Prefs.FILTER_APPS,
                                    new HashSet<>(Arrays.asList("org.example.a", "a.b.c"))).commit();

                    String text = SettingsIo.export(a);
                    assertTrue("the file must name its format", text.contains("aod-settings"));
                    assertTrue("the file must be readable text", text.contains("\n"));
                    assertTrue("the app list must be in the file", text.contains("a.b.c"));

                    p.edit().putInt(Prefs.TIMEOUT_SEC, 60)
                            .putString(Prefs.STYLE, Prefs.STYLE_DOT)
                            .putStringSet(Prefs.FILTER_APPS, new HashSet<>()).commit();

                    SettingsIo.Result r = SettingsIo.apply(a, text);
                    assertTrue("import failed: " + r.error, r.ok());
                    assertEquals(900, Prefs.timeoutSec(a));
                    assertEquals(Prefs.STYLE_EDGE, p.getString(Prefs.STYLE, ""));
                    assertTrue(p.getStringSet(Prefs.FILTER_APPS, new HashSet<>()).contains("a.b.c"));
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
                finally
                {
                    p.edit().putInt(Prefs.TIMEOUT_SEC, oldTimeout)
                            .putString(Prefs.STYLE, oldStyle)
                            .putStringSet(Prefs.FILTER_APPS, oldApps).commit();
                }
            });
        }
    }

    @Test
    public void importRejectsForeignFiles()
    {
        assertFalse("plain JSON is not a settings file",
                SettingsIo.apply(target(), "{\"hello\":1}").ok());
        assertFalse("rubbish is not a settings file",
                SettingsIo.apply(target(), "not json at all").ok());
        assertTrue("a newer file must still import what this version knows",
                SettingsIo.apply(target(),
                        "{\"format\":\"aod-settings\",\"version\":99,\"settings\":{}}").ok());
        assertFalse("a file without a version is refused",
                SettingsIo.apply(target(),
                        "{\"format\":\"aod-settings\",\"settings\":{}}").ok());
    }

    @Test
    public void exportWritesUntouchedSettingsToo()
    {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class))
        {
            s.onActivity(a -> {
                SharedPreferences p = Prefs.get(a);
                Map<String, ?> saved = new HashMap<>(p.getAll());
                try
                {
                    p.edit().clear().commit();
                    String text = SettingsIo.export(a);
                    JSONObject settings = new JSONObject(text).getJSONObject("settings");
                    for (SettingsSchema.Field f : SettingsSchema.FIELDS)
                    {
                        assertTrue("untouched setting missing from the backup: " + f.key,
                                settings.has(f.key));
                    }
                    assertEquals(SettingsSchema.defInt(Prefs.TIMEOUT_SEC),
                            settings.getInt(Prefs.TIMEOUT_SEC));
                    assertEquals(SettingsSchema.defBool(Prefs.AWAKE_PANEL),
                            settings.getBoolean(Prefs.AWAKE_PANEL));
                    assertEquals(SettingsSchema.defString(Prefs.STYLE),
                            settings.getString(Prefs.STYLE));
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
                finally
                {
                    restore(p, saved);
                }
            });
        }
    }

    @Test
    public void importKeepsValidSettingsAndIgnoresRemovedOnes()
    {
        String file = "{\"format\":\"aod-settings\",\"version\":99,\"settings\":{"
                + "\"timeout_sec\":900,\"a_setting_from_the_future\":true,"
                + "\"another_removed_one\":\"x\"}}";
        SharedPreferences p = Prefs.get(target());
        int old = Prefs.timeoutSec(target());
        try
        {
            SettingsIo.Result r = SettingsIo.apply(target(), file);
            assertTrue("import failed: " + r.error, r.ok());
            assertEquals("the known setting must be applied", 1, r.applied);
            assertEquals("removed settings must be ignored", 2, r.unknown);
            assertEquals(900, Prefs.timeoutSec(target()));
        }
        finally
        {
            p.edit().putInt(Prefs.TIMEOUT_SEC, old).commit();
        }
    }

    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences p, Map<String, ?> saved)
    {
        SharedPreferences.Editor ed = p.edit().clear();
        for (Map.Entry<String, ?> e : saved.entrySet())
        {
            Object v = e.getValue();
            if (v instanceof Boolean) { ed.putBoolean(e.getKey(), (Boolean) v); }
            else if (v instanceof Integer) { ed.putInt(e.getKey(), (Integer) v); }
            else if (v instanceof String) { ed.putString(e.getKey(), (String) v); }
            else if (v instanceof Set) { ed.putStringSet(e.getKey(), new HashSet<>((Set<String>) v)); }
        }
        ed.commit();
    }
}
