package com.zoffcc.applications.aod;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * The settings screen used to be one very long column. This class turns it
 * into named sections that fold away, and wires the small search field above
 * the list.
 *
 * Layout contract (see res/layout/activity_main.xml):
 *   - every section is a header row plus a body container;
 *   - the header's children are, in order: chevron, title, count;
 *   - the header's tag is the section key, used for the stored open state;
 *   - every direct child of a body is one row, tagged either "info" (the
 *     section's own explanation) or "row", optionally "row|extra keywords".
 *
 * Searching shows the matching rows of every section at once, opens the
 * sections that have a match and hides the rest. Clearing the field restores
 * the folded state the user left behind, which is stored per section.
 */
final class SettingsSections
{
    private static final int[] HEADERS = {
            R.id.hdr_basics, R.id.hdr_look, R.id.hdr_time, R.id.hdr_icons, R.id.hdr_icon_pos,
            R.id.hdr_icon_count, R.id.hdr_clock, R.id.hdr_when, R.id.hdr_cutout, R.id.hdr_backup,
            R.id.hdr_advanced
    };
    private static final int[] BODIES = {
            R.id.body_basics, R.id.body_look, R.id.body_time, R.id.body_icons, R.id.body_icon_pos,
            R.id.body_icon_count, R.id.body_clock, R.id.body_when, R.id.body_cutout, R.id.body_backup,
            R.id.body_advanced
    };
    /** Only the basics are open on a fresh install; the rest fold away. */
    private static final boolean[] DEFAULT_OPEN = {
            true, false, false, false, false, false, false, false, false, false, false
    };
    private static final String OPEN_KEY = "sec_open_";

    private static final class Row
    {
        final View view;
        final String text;
        final boolean info;

        Row(View view, String text, boolean info)
        {
            this.view = view;
            this.text = text;
            this.info = info;
        }
    }

    private static final class Section
    {
        final ViewGroup header;
        final ImageView chevron;
        final TextView count;
        final ViewGroup body;
        final String key;
        final String title;
        final boolean defaultOpen;
        final ArrayList<Row> rows = new ArrayList<>();
        int settings;
        boolean open;

        Section(ViewGroup header, ViewGroup body, String key, String title, boolean defaultOpen)
        {
            this.header = header;
            this.body = body;
            this.key = key;
            this.title = title;
            this.defaultOpen = defaultOpen;
            this.chevron = (ImageView) header.getChildAt(0);
            this.count = (TextView) header.getChildAt(2);
        }
    }

    private final Activity act;
    private final SharedPreferences prefs;
    private final ArrayList<Section> sections = new ArrayList<>();
    private final View header_block;
    private final TextView no_match;
    private final View scroll;
    private final EditText search;
    private final View clear;

    static void install(Activity a, SharedPreferences prefs)
    {
        new SettingsSections(a, prefs);
    }

    private SettingsSections(Activity a, SharedPreferences p)
    {
        act = a;
        prefs = p;
        header_block = a.findViewById(R.id.header_block);
        no_match = a.findViewById(R.id.txt_no_match);
        scroll = a.findViewById(R.id.settings_scroll);
        search = a.findViewById(R.id.et_settings_search);
        clear = a.findViewById(R.id.btn_settings_search_clear);

        for (int i = 0; i < HEADERS.length; i++)
        {
            ViewGroup header = a.findViewById(HEADERS[i]);
            ViewGroup body = a.findViewById(BODIES[i]);
            if (header == null || body == null) { continue; }
            String key = String.valueOf(header.getTag());
            String title = ((TextView) header.getChildAt(1)).getText().toString();
            Section s = new Section(header, body, key, title, DEFAULT_OPEN[i]);
            collectRows(s);
            header.setAccessibilityHeading(true);
            header.setOnClickListener(v -> {
                hideKeyboard();
                setOpen(s, !s.open, true);
            });
            sections.add(s);
        }

        search.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) { apply(); }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard();
            search.clearFocus();
            return true;
        });
        clear.setOnClickListener(v -> {
            search.setText("");
            search.clearFocus();
            hideKeyboard();
        });

        apply();
    }

    /** One searchable string per row: its labels, its explanation, its choices. */
    private void collectRows(Section s)
    {
        for (int i = 0; i < s.body.getChildCount(); i++)
        {
            View child = s.body.getChildAt(i);
            String tag = child.getTag() instanceof String ? (String) child.getTag() : "row";
            boolean info = tag.startsWith("info");
            StringBuilder sb = new StringBuilder();
            collectText(child, sb);
            int bar = tag.indexOf('|');
            if (bar >= 0) { sb.append(' ').append(tag.substring(bar + 1)); }
            s.rows.add(new Row(child, sb.toString().toLowerCase(Locale.ROOT), info));
            if (!info) { s.settings++; }
        }
    }

    private static void collectText(View v, StringBuilder sb)
    {
        if (v instanceof TextView)
        {
            sb.append(((TextView) v).getText()).append(' ');
        }
        if (v.getContentDescription() != null)
        {
            sb.append(v.getContentDescription()).append(' ');
        }
        if (v instanceof Spinner)
        {
            // the choices are part of the setting: "edge" must find the style
            SpinnerAdapter adapter = ((Spinner) v).getAdapter();
            if (adapter != null)
            {
                for (int i = 0; i < adapter.getCount(); i++)
                {
                    Object item = adapter.getItem(i);
                    if (item != null) { sb.append(item).append(' '); }
                }
            }
        }
        if (v instanceof ViewGroup)
        {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { collectText(g.getChildAt(i), sb); }
        }
    }

    private void apply()
    {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean searching = !query.isEmpty();
        String[] words = searching ? query.split("\\s+") : new String[0];

        clear.setVisibility(searching ? View.VISIBLE : View.GONE);
        // the permission block is status, not a setting: it steps aside
        header_block.setVisibility(searching ? View.GONE : View.VISIBLE);

        int shown = 0;
        for (Section s : sections)
        {
            boolean titleHit = searching && matches(s.title.toLowerCase(Locale.ROOT), words);
            boolean anyHit = false;
            int hits = 0;
            for (Row r : s.rows)
            {
                boolean show = !searching || titleHit || matches(r.text, words);
                r.view.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show)
                {
                    anyHit = true;
                    if (!r.info) { hits++; }
                }
            }
            boolean visible = !searching || titleHit || anyHit;
            s.header.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) { shown++; }
            if (searching)
            {
                setOpen(s, visible, false);
                s.count.setText(countText(hits));
            }
            else
            {
                setOpen(s, prefs.getBoolean(OPEN_KEY + s.key, s.defaultOpen), false);
                s.count.setText(countText(s.settings));
            }
        }
        no_match.setVisibility(searching && shown == 0 ? View.VISIBLE : View.GONE);
        if (scroll != null) { scroll.scrollTo(0, 0); }
    }

    private static boolean matches(String haystack, String[] words)
    {
        for (String w : words)
        {
            if (!haystack.contains(w)) { return false; }
        }
        return true;
    }

    private String countText(int n)
    {
        return act.getResources().getQuantityString(R.plurals.sec_settings_count, n, n);
    }

    private void setOpen(Section s, boolean open, boolean store)
    {
        s.open = open;
        s.body.setVisibility(open ? View.VISIBLE : View.GONE);
        s.chevron.setRotation(open ? 0f : -90f);
        s.header.setContentDescription(act.getString(
                open ? R.string.sec_close_hint : R.string.sec_open_hint, s.title));
        if (store) { prefs.edit().putBoolean(OPEN_KEY + s.key, open).apply(); }
    }

    private void hideKeyboard()
    {
        try
        {
            InputMethodManager imm =
                    (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) { imm.hideSoftInputFromWindow(search.getWindowToken(), 0); }
        }
        catch (Exception ignored) {}
    }
}
