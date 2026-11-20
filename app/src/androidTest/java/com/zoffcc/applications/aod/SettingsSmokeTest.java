package com.zoffcc.applications.aod;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
}
