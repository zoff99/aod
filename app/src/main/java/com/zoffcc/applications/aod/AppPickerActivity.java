package com.zoffcc.applications.aod;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen app selection: searchable list of every app that can be
 * launched, plus every app that has posted a notification since install
 * (some of those have no launcher icon at all), each with its icon.
 *
 * The list can be narrowed to selected or not-selected apps, which makes a
 * long list easy to finish, and the view chosen is remembered. Selection is saved instantly; long press sets a per-app light
 * color. Designed for very large app lists (background loading, lazy icons,
 * diffed updates, fast scrolling).
 */
public class AppPickerActivity extends AppCompatActivity implements AppListAdapter.Listener
{
    /** Launch with this extra to pick the always-allow list instead of the filter. */
    public static final String EXTRA_MODE = "mode";
    public static final String MODE_PERSISTENT = "persistent";

    private SharedPreferences prefs;
    private boolean persistent = false;
    private final Set<String> selected = new HashSet<>();
    private final List<AppListAdapter.AppEntry> all = new ArrayList<>();
    private List<AppListAdapter.AppEntry> filtered = new ArrayList<>();

    private AppListAdapter adapter;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView count;
    private TextView empty;
    private ProgressBar progress;
    private EditText search;
    private View clearSearch;
    private String mode = Prefs.PICKER_ALL;
    private volatile boolean loaded = false;
    private final Runnable filterRunnable = this::refilter;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        ActionBar bar = getSupportActionBar();
        if (bar != null) { bar.hide(); }
        setContentView(R.layout.activity_app_picker);

        prefs = Prefs.get(this);
        persistent = MODE_PERSISTENT.equals(getIntent().getStringExtra(EXTRA_MODE));
        // always work on a copy: never mutate the set returned by getStringSet
        selected.addAll(persistent ? Prefs.persistentApps(this)
                : prefs.getStringSet(Prefs.FILTER_APPS, new HashSet<>()));
        if (persistent)
        {
            ((TextView) findViewById(R.id.txt_title)).setText(R.string.picker_persistent_title);
            ((TextView) findViewById(R.id.txt_hint)).setText(R.string.picker_persistent_hint);
        }

        count = findViewById(R.id.txt_count);
        empty = findViewById(R.id.txt_empty);
        progress = findViewById(R.id.progress);
        search = findViewById(R.id.et_search);
        clearSearch = findViewById(R.id.btn_clear_search);

        RecyclerView list = findViewById(R.id.app_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppListAdapter(this, selected, this);
        list.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        clearSearch.setOnClickListener(v -> search.setText(""));
        findViewById(R.id.btn_all).setOnClickListener(v -> applyToFiltered(true));
        findViewById(R.id.btn_none).setOnClickListener(v -> applyToFiltered(false));

        bindFilter();

        search.addTextChangedListener(new TextWatcher()
        {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s)
            {
                clearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                main.removeCallbacks(filterRunnable);
                main.postDelayed(filterRunnable, 120);
            }
        });

        updateCount();
        loadApps();
    }

    private void loadApps()
    {
        worker.execute(() -> {
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(main, 0);
            List<AppListAdapter.AppEntry> entries = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo ri : resolved)
            {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) { continue; }
                String label;
                try { label = ri.loadLabel(getPackageManager()).toString(); }
                catch (Exception e) { label = pkg; }
                entries.add(new AppListAdapter.AppEntry(pkg, label, Prefs.appColor(this, pkg)));
            }
            // apps without a launcher entry: they still post notifications,
            // so they must be filterable too. Package names come from the
            // listener's own memory, never from notification content.
            for (String pkg : Prefs.seenPackages(this))
            {
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) { continue; }
                String label;
                try
                {
                    label = getPackageManager()
                            .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))
                            .toString();
                }
                catch (Exception e) { label = pkg; }
                entries.add(new AppListAdapter.AppEntry(pkg, label, Prefs.appColor(this, pkg)));
            }
            Collator coll = Collator.getInstance();
            coll.setStrength(Collator.SECONDARY);
            entries.sort((a, b) -> coll.compare(a.label, b.label));
            this.main.post(() -> {
                all.clear();
                all.addAll(entries);
                loaded = true;
                progress.setVisibility(View.GONE);
                refilter();
                updateCount();
            });
        });
    }

    /** All apps / only the selected ones / only the ones not picked yet. */
    private void bindFilter()
    {
        Spinner sp = findViewById(R.id.sp_picker_filter);
        ArrayAdapter<CharSequence> fa = ArrayAdapter.createFromResource(this,
                R.array.picker_filter_entries, R.layout.item_spinner);
        fa.setDropDownViewResource(R.layout.item_spinner_dropdown);
        sp.setAdapter(fa);
        final String[] modes = {Prefs.PICKER_ALL, Prefs.PICKER_SELECTED, Prefs.PICKER_UNSELECTED};
        mode = Prefs.pickerFilter(this);
        sp.setSelection(indexOf(modes, mode));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
        {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
            {
                String next = modes[position];
                if (next.equals(mode)) { return; }   // spinners report on attach
                mode = next;
                prefs.edit().putString(Prefs.PICKER_FILTER, next).apply();
                refilter();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private static int indexOf(String[] values, String value)
    {
        for (int i = 0; i < values.length; i++) { if (values[i].equals(value)) { return i; } }
        return 0;
    }

    private void refilter()
    {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<AppListAdapter.AppEntry> out = new ArrayList<>();
        for (AppListAdapter.AppEntry e : all)
        {
            boolean picked = selected.contains(e.pkg);
            if (Prefs.PICKER_SELECTED.equals(mode) && !picked) { continue; }
            if (Prefs.PICKER_UNSELECTED.equals(mode) && picked) { continue; }
            if (q.isEmpty() || e.searchKey.contains(q)) { out.add(e); }
        }
        filtered = out;
        adapter.submit(out);
        if (!loaded || !out.isEmpty())
        {
            empty.setVisibility(View.GONE);
            return;
        }
        if (!q.isEmpty()) { empty.setText(R.string.picker_empty); }
        else if (Prefs.PICKER_SELECTED.equals(mode)) { empty.setText(R.string.picker_empty_none); }
        else if (Prefs.PICKER_UNSELECTED.equals(mode)) { empty.setText(R.string.picker_empty_all); }
        else { empty.setText(R.string.picker_empty); }
        empty.setVisibility(View.VISIBLE);
    }

    @Override
    public void onToggle(AppListAdapter.AppEntry entry, boolean isSelected)
    {
        if (isSelected) { selected.add(entry.pkg); }
        else { selected.remove(entry.pkg); }
        persist();
        updateCount();
        if (Prefs.PICKER_ALL.equals(mode)) { adapter.refresh(entry.pkg); }
        else { refilter(); }   // the row no longer belongs in this view
    }

    @Override
    public void onLongPress(AppListAdapter.AppEntry entry, int position)
    {
        ColorDialogs.showAppColor(this, entry.pkg, entry.label, () -> {
            entry.colorOverride = Prefs.appColor(this, entry.pkg);
            if (position >= 0) { adapter.notifyItemChanged(position); }
            else { adapter.refresh(entry.pkg); }
            sendReeval();
        });
    }

    @SuppressLint("NotifyDataSetChanged")
    private void applyToFiltered(boolean select)
    {
        for (AppListAdapter.AppEntry e : filtered)
        {
            if (select) { selected.add(e.pkg); }
            else { selected.remove(e.pkg); }
        }
        persist();
        updateCount();
        // Selection is stored outside the entries, so a diff would see no
        // change; membership is untouched, so rebind everything in place.
        adapter.notifyDataSetChanged();
        if (!Prefs.PICKER_ALL.equals(mode)) { refilter(); }
        sendReeval();
    }

    private void persist()
    {
        prefs.edit().putStringSet(persistent ? Prefs.PERSISTENT_APPS : Prefs.FILTER_APPS,
                new HashSet<>(selected)).apply();
    }

    private void updateCount()
    {
        int n = 0;
        for (AppListAdapter.AppEntry e : all)
        {
            if (selected.contains(e.pkg)) { n++; }
        }
        count.setText(getString(R.string.picker_selected_count, n, all.size()));
    }

    private void sendReeval()
    {
        Intent i = new Intent(notificationobserver.ACTION_REEVAL);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    @Override
    protected void onDestroy()
    {
        main.removeCallbacks(filterRunnable);
        worker.shutdownNow();
        adapter.shutdown();
        sendReeval();
        super.onDestroy();
    }
}
