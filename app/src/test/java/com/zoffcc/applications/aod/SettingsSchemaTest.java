package com.zoffcc.applications.aod;

import org.junit.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Host tests for the backup schema: every setting must be covered, ranges
 * must match what the UI offers, and state must stay out of a backup.
 * ./gradlew :app:testDebugUnitTest
 */
public class SettingsSchemaTest
{
    /** Constants in Prefs that are values, not preference keys. */
    private static final List<String> VALUE_CONSTANTS = Arrays.asList(
            "FILE", "STYLE_RING", "STYLE_EDGE", "STYLE_DOT", "STYLE_ICONS",
            "ANIM_PULSE", "ANIM_ROTATE", "ANIM_FLASH", "ANIM_STEADY",
            "PICKER_ALL", "PICKER_SELECTED", "PICKER_UNSELECTED",
            "ICON_SRC_APP", "ICON_SRC_NOTIFICATION",
            "ICON_TINT_COLOR", "ICON_TINT_WHITE", "ICON_TINT_BLACK",
            "ICON_Y_CAMERA", "ICON_Y_TOP", "ICON_Y_BOTTOM",
            "ICON_COUNT_SRC_NOTIFICATIONS", "ICON_COUNT_SRC_APP");

    @Test
    public void everyPreferenceKeyIsExportedOrExplicitlySkipped() throws Exception
    {
        for (java.lang.reflect.Field f : Prefs.class.getFields())
        {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) { continue; }
            if (VALUE_CONSTANTS.contains(f.getName())) { continue; }
            String key = (String) f.get(null);
            assertTrue("setting " + key + " is neither in the schema nor skipped",
                    SettingsSchema.find(key) != null || SettingsSchema.isStateKey(key));
        }
    }

    @Test
    public void noKeyAppearsTwice()
    {
        Set<String> seen = new HashSet<>();
        for (SettingsSchema.Field f : SettingsSchema.FIELDS)
        {
            assertTrue("duplicate key " + f.key, seen.add(f.key));
        }
    }

    @Test
    public void nothingStatefulIsExported()
    {
        for (SettingsSchema.Field f : SettingsSchema.FIELDS)
        {
            assertFalse("state key in the schema: " + f.key, SettingsSchema.isStateKey(f.key));
        }
        assertTrue(SettingsSchema.isStateKey("sec_open_basics"));
        assertTrue(SettingsSchema.isStateKey(Prefs.SEEN_PKGS));
        assertTrue(SettingsSchema.isStateKey(Prefs.ACCESS_SEEN));
        assertTrue(SettingsSchema.isStateKey(Prefs.IGNORE_PERSISTENT));
        assertFalse(SettingsSchema.isStateKey(Prefs.TIMEOUT_SEC));
    }

    @Test
    public void intRangesAreOrdered()
    {
        for (SettingsSchema.Field f : SettingsSchema.FIELDS)
        {
            if (f.type == SettingsSchema.INT)
            {
                assertTrue("range of " + f.key, f.min <= f.max);
            }
        }
    }

    @Test
    public void aHandEditedFileCannotAskForAbsurdValues()
    {
        assertEquals(10800, SettingsSchema.clamp(SettingsSchema.find(Prefs.TIMEOUT_SEC), 999999));
        assertEquals(60, SettingsSchema.clamp(SettingsSchema.find(Prefs.TIMEOUT_SEC), -5));
        assertEquals(100, SettingsSchema.clamp(SettingsSchema.find(Prefs.BRIGHTNESS), 500));
        assertEquals(5, SettingsSchema.clamp(SettingsSchema.find(Prefs.BRIGHTNESS), 0));
        assertEquals(96, SettingsSchema.clamp(SettingsSchema.find(Prefs.ICON_SIZE_DP), 4000));
        assertEquals(1439, SettingsSchema.clamp(SettingsSchema.find(Prefs.NIGHT_START), 9999));
    }

    @Test
    public void aColorKeepsItsFullIntRange()
    {
        SettingsSchema.Field color = SettingsSchema.find(Prefs.COLOR);
        assertEquals(0xFF00E5FF, SettingsSchema.clamp(color, 0xFF00E5FF));
        assertEquals(-1, SettingsSchema.clamp(color, -1));
    }

    @Test
    public void clampIgnoresOtherTypes()
    {
        assertEquals(7, SettingsSchema.clamp(SettingsSchema.find(Prefs.STYLE), 7));
        assertEquals(7, SettingsSchema.clamp(SettingsSchema.find(Prefs.ENABLED), 7));
    }

    @Test
    public void choicesRejectUnknownValues()
    {
        SettingsSchema.Field style = SettingsSchema.find(Prefs.STYLE);
        assertTrue(SettingsSchema.accepts(style, Prefs.STYLE_ICONS));
        assertFalse(SettingsSchema.accepts(style, "neon"));
        assertFalse(SettingsSchema.accepts(style, null));
        SettingsSchema.Field mode = SettingsSchema.find(Prefs.PERSISTENT_MODE);
        assertTrue(SettingsSchema.accepts(mode, PersistentPolicy.CALLS_APPS));
        assertFalse(SettingsSchema.accepts(mode, "whatever"));
    }

    @Test
    public void freeTextSurvivesSpecialCharacters()
    {
        SettingsSchema.Field colors = SettingsSchema.find(Prefs.APP_COLORS);
        assertNotNull(colors);
        String awkward = "org.example=#FF00E5FF;a.b.c=\"quoted\";x\\y=z;u=\u00e9\u4e2d\u6587";
        assertTrue(SettingsSchema.accepts(colors, awkward));
    }

    @Test
    public void theTwoAppListsAreSets()
    {
        assertEquals(SettingsSchema.SET, SettingsSchema.find(Prefs.FILTER_APPS).type);
        assertEquals(SettingsSchema.SET, SettingsSchema.find(Prefs.PERSISTENT_APPS).type);
    }

    @Test
    public void unknownKeysAreNotFound()
    {
        assertNull(SettingsSchema.find("no_such_setting"));
        assertNull(SettingsSchema.find(null));
    }

    @Test
    public void everyFieldCarriesADefaultOfItsOwnType()
    {
        for (SettingsSchema.Field f : SettingsSchema.FIELDS)
        {
            switch (f.type)
            {
                case SettingsSchema.BOOL:
                    assertTrue("bool default missing for " + f.key, f.def instanceof Boolean);
                    break;
                case SettingsSchema.INT:
                    assertTrue("int default missing for " + f.key, f.def instanceof Integer);
                    break;
                case SettingsSchema.STRING:
                    assertTrue("string default missing for " + f.key, f.def instanceof String);
                    break;
                default:
                    break;   // sets: empty, or a living default resolved at runtime
            }
        }
    }

    @Test
    public void everyIntDefaultIsInsideItsOwnRange()
    {
        for (SettingsSchema.Field f : SettingsSchema.FIELDS)
        {
            if (f.type != SettingsSchema.INT) { continue; }
            int def = (Integer) f.def;
            assertTrue("default of " + f.key + " is outside its range",
                    def >= f.min && def <= f.max);
            assertEquals(def, SettingsSchema.clamp(f, def));
        }
    }

    @Test
    public void everyStringDefaultIsAnAllowedChoice()
    {
        for (SettingsSchema.Field f : SettingsSchema.FIELDS)
        {
            if (f.type != SettingsSchema.STRING) { continue; }
            assertTrue("default of " + f.key + " is not an allowed value",
                    SettingsSchema.accepts(f, (String) f.def));
        }
    }

    @Test
    public void theDefaultsTheAppShipsWith()
    {
        // locked on purpose: a change here is a change of behaviour and must
        // be a deliberate edit, not a side effect
        assertEquals(3600, SettingsSchema.defInt(Prefs.TIMEOUT_SEC));
        assertEquals(18, SettingsSchema.defInt(Prefs.ICON_SIZE_DP));
        assertEquals(30, SettingsSchema.defInt(Prefs.BRIGHTNESS));
        assertEquals(Prefs.DEFAULT_COLOR, SettingsSchema.defInt(Prefs.COLOR));
        assertEquals(Prefs.STYLE_ICONS, SettingsSchema.defString(Prefs.STYLE));
        assertEquals(PersistentPolicy.CALLS_APPS, SettingsSchema.defString(Prefs.PERSISTENT_MODE));
        assertTrue(SettingsSchema.defBool(Prefs.ENABLED));
        assertFalse(SettingsSchema.defBool(Prefs.AWAKE_PANEL));
        assertFalse(SettingsSchema.defBool(Prefs.ICON_COUNT_BADGE));
    }

    @Test
    public void defaultLookupsOfUnknownKeysAreHarmless()
    {
        assertFalse(SettingsSchema.defBool("no_such_key"));
        assertEquals(0, SettingsSchema.defInt("no_such_key"));
        assertEquals("", SettingsSchema.defString("no_such_key"));
        assertEquals(0, SettingsSchema.defInt(Prefs.STYLE));      // wrong type
        assertEquals("", SettingsSchema.defString(Prefs.ENABLED));
    }
}
