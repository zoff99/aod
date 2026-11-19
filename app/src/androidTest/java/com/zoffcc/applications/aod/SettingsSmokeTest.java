package com.zoffcc.applications.aod;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Device smoke test: the settings screen builds and the two services the app
 * depends on are declared. Run with ./gradlew :app:connectedDebugAndroidTest
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
}
