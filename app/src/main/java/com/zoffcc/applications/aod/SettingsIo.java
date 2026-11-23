package com.zoffcc.applications.aod;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Settings backup as plain, pretty-printed JSON.
 *
 * Why this format: org.json ships with Android, so nothing has to be added;
 * the file is readable and hand-editable in any text editor; and JSON escapes
 * quotes, backslashes and control characters while writing everything else as
 * UTF-8, so even awkward values survive a round trip unchanged.
 *
 * Every key in {@link SettingsSchema} is written with its effective value,
 * defaults included, so a backup is a complete snapshot rather than a diff
 * against whatever the defaults happened to be. On the way back only those
 * keys are accepted, with their type and range checked; keys this version
 * does not have are ignored, so files from newer versions still import.
 * The one renamed setting is carried over: a pre-version-3 "brightness"
 * percentage imports as the item visibility when no new key is present.
 */
public final class SettingsIo
{
    private SettingsIo() {}

    public static final String FORMAT = "aod-settings";
    public static final int VERSION = 3;
    public static final String FILE_NAME = "aod-settings.json";
    public static final String MIME = "application/json";

    /** The whole backup as text. Sorted sets keep the file stable and diffable. */
    public static String export(Context c) throws Exception
    {
        SharedPreferences p = Prefs.get(c);
        JSONObject settings = new JSONObject();
        // Every setting is written, including the ones the user never touched:
        // their effective value is written, not "absent". Otherwise a later
        // version with different defaults would silently change them on import.
        for (SettingsSchema.Field f : SettingsSchema.FIELDS)
        {
            switch (f.type)
            {
                case SettingsSchema.BOOL:
                    settings.put(f.key, p.getBoolean(f.key, SettingsSchema.defBool(f.key)));
                    break;
                case SettingsSchema.INT:
                    // the visibility has a migrating default (the legacy
                    // brightness percentage), so ask for its effective value
                    // rather than the raw store, like the always-allow list
                    settings.put(f.key, Prefs.AOD_VISIBILITY.equals(f.key)
                            ? Prefs.visibility(c) : p.getInt(f.key, SettingsSchema.defInt(f.key)));
                    break;
                case SettingsSchema.STRING:
                    settings.put(f.key, p.getString(f.key, SettingsSchema.defString(f.key)));
                    break;
                case SettingsSchema.SET:
                    // the always-allow list has a living default (the dialer),
                    // so ask for its effective value rather than an empty set
                    Set<String> raw = Prefs.PERSISTENT_APPS.equals(f.key)
                            ? Prefs.persistentApps(c)
                            : p.getStringSet(f.key, new HashSet<>());
                    JSONArray arr = new JSONArray();
                    for (String v : new TreeSet<>(raw)) { arr.put(v); }
                    settings.put(f.key, arr);
                    break;
                default:
                    break;
            }
        }
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("app", BuildConfig.VERSION_NAME);
        root.put("exported", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()));
        root.put("settings", settings);
        return root.toString(2) + "\n";
    }

    /** Read a picked file as UTF-8, with a size cap: a wrong pick must not OOM. */
    public static String readUtf8(java.io.File file) throws Exception
    {
        final long max = 1024L * 1024L;
        if (file.length() > max) { throw new Exception("file too large"); }
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = new java.io.FileInputStream(file))
        {
            byte[] chunk = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(chunk)) != -1)
            {
                total += n;
                if (total > max) { throw new Exception("file too large"); }
                buf.write(chunk, 0, n);
            }
        }
        return new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** What an import did, for the message shown afterwards. */
    public static final class Result
    {
        public int applied;
        public int skipped;
        public int unknown;
        public String error;

        public boolean ok() { return error == null; }
    }

    /**
     * Read a backup and apply it. Nothing is written unless the file is a
     * settings file of a version we understand; individual values that do not
     * fit their type or range are skipped or clamped, never fatal.
     */
    public static Result apply(Context c, String text)
    {
        Result r = new Result();
        JSONObject root;
        try
        {
            root = new JSONObject(text);
        }
        catch (Exception e)
        {
            r.error = "not a JSON settings file";
            return r;
        }
        if (!FORMAT.equals(root.optString("format")))
        {
            r.error = "not an AoD settings file";
            return r;
        }
        int version = root.optInt("version", 0);
        // Newer files are welcome: every key is validated on its own, so a
        // setting this version no longer has is simply ignored. Only a file
        // that does not claim a version at all is refused.
        if (version < 1)
        {
            r.error = "the file has no settings version";
            return r;
        }
        JSONObject settings = root.optJSONObject("settings");
        if (settings == null)
        {
            r.error = "the file contains no settings";
            return r;
        }

        SharedPreferences.Editor ed = Prefs.get(c).edit();

        // Legacy (files before version 3): the slider was an own panel
        // brightness request, stored as "brightness" (5..100). Its percentage
        // carries over as the item visibility, so an old backup restores the
        // dimness the user had dialled in. A file that already has the new
        // key wins; a file with neither leaves the current value alone.
        if (!settings.has(Prefs.AOD_VISIBILITY) && settings.has(Prefs.BRIGHTNESS))
        {
            try
            {
                SettingsSchema.Field f = SettingsSchema.find(Prefs.AOD_VISIBILITY);
                ed.putInt(Prefs.AOD_VISIBILITY, SettingsSchema.clamp(f, settings.getInt(Prefs.BRIGHTNESS)));
                r.applied++;
            }
            catch (Exception e) { r.skipped++; }
        }

        Iterator<String> keys = settings.keys();
        while (keys.hasNext())
        {
            String key = keys.next();
            if (Prefs.BRIGHTNESS.equals(key)) { continue; }   // legacy, handled above
            SettingsSchema.Field f = SettingsSchema.find(key);
            if (f == null)
            {
                r.unknown++;
                continue;
            }
            try
            {
                switch (f.type)
                {
                    case SettingsSchema.BOOL:
                        ed.putBoolean(key, settings.getBoolean(key));
                        break;
                    case SettingsSchema.INT:
                        ed.putInt(key, SettingsSchema.clamp(f, settings.getInt(key)));
                        break;
                    case SettingsSchema.STRING:
                        String value = settings.getString(key);
                        if (!SettingsSchema.accepts(f, value))
                        {
                            r.skipped++;
                            continue;
                        }
                        ed.putString(key, value);
                        break;
                    case SettingsSchema.SET:
                        JSONArray arr = settings.getJSONArray(key);
                        Set<String> values = new HashSet<>();
                        for (int i = 0; i < arr.length(); i++)
                        {
                            String v = arr.optString(i, null);
                            if (v != null && !v.isEmpty()) { values.add(v); }
                        }
                        ed.putStringSet(key, values);
                        break;
                    default:
                        r.skipped++;
                        continue;
                }
                r.applied++;
            }
            catch (Exception e)
            {
                r.skipped++;
            }
        }
        ed.apply();
        Dbg.log("settings", "import: applied=" + r.applied + " skipped=" + r.skipped
                + " unknown=" + r.unknown);
        return r;
    }
}
