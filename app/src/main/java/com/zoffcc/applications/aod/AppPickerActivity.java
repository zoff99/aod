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
import android.widget.EditText;
import android.widget.ProgressBar;
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
 * Full-screen app selection: searchable list of every launchable app with
 * its icon. Selection is saved instantly; long press sets a per-app light
 * color. Designed for very large app lists (background loading, lazy icons,
 * diffed updates, fast scrolling).
 */
public class AppPickerActivity extends AppCompatActivity implements AppListAdapter.Listener
{
    private SharedPreferences prefs;
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
        // always work on a copy: never mutate the set returned by getStringSet
        selected.addAll(prefs.getStringSet(Prefs.FILTER_APPS, new HashSet<>()));

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

    private void refilter()
    {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<AppListAdapter.AppEntry> out = new ArrayList<>();
        for (AppListAdapter.AppEntry e : all)
        {
            if (q.isEmpty() || e.searchKey.contains(q)) { out.add(e); }
        }
        filtered = out;
        adapter.submit(out);
        empty.setVisibility(loaded && out.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onToggle(AppListAdapter.AppEntry entry, boolean isSelected)
    {
        if (isSelected) { selected.add(entry.pkg); }
        else { selected.remove(entry.pkg); }
        persist();
        updateCount();
        adapter.refresh(entry.pkg);
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
        adapter.notifyDataSetChanged();
        sendReeval();
    }

    private void persist()
    {
        prefs.edit().putStringSet(Prefs.FILTER_APPS, new HashSet<>(selected)).apply();
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
