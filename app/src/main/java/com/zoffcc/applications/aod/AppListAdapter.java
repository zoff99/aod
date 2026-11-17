package com.zoffcc.applications.aod;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rows of the app picker: icon, name, package, optional per-app color dot and
 * an on/off switch. Icons are decoded lazily on a worker thread and cached,
 * so even very long app lists scroll smoothly.
 */
class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.Holder>
{
    static class AppEntry
    {
        final String pkg;
        final String label;
        final String searchKey;
        int colorOverride;

        AppEntry(String pkg, String label, int colorOverride)
        {
            this.pkg = pkg;
            this.label = label;
            this.colorOverride = colorOverride;
            this.searchKey = (label + " " + pkg).toLowerCase(Locale.ROOT);
        }
    }

    interface Listener
    {
        void onToggle(AppEntry entry, boolean selected);
        void onLongPress(AppEntry entry, int position);
    }

    private final Context ctx;
    private final PackageManager pm;
    private final Set<String> selected;   // shared with the activity, main thread only
    private final Listener listener;
    private final List<AppEntry> items = new ArrayList<>();
    private List<AppEntry> submitted = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Drawable> icons = new LruCache<>(120);

    AppListAdapter(Context ctx, Set<String> selected, Listener listener)
    {
        this.ctx = ctx;
        this.pm = ctx.getPackageManager();
        this.selected = selected;
        this.listener = listener;
    }

    /**
     * Diffs on the worker thread, then dispatches the result on the main
     * thread. Each diff is computed against the previously submitted list
     * (not the currently displayed one): submits and dispatches are both
     * FIFO, so queued updates can never disagree with the adapter state.
     */
    void submit(final List<AppEntry> next)
    {
        final List<AppEntry> old = submitted;
        submitted = next;
        final Set<String> selSnapshot = new HashSet<>(selected);
        io.execute(() -> {
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback()
            {
                @Override public int getOldListSize() { return old.size(); }
                @Override public int getNewListSize() { return next.size(); }

                @Override
                public boolean areItemsTheSame(int o, int n)
                {
                    return old.get(o).pkg.equals(next.get(n).pkg);
                }

                @Override
                public boolean areContentsTheSame(int o, int n)
                {
                    AppEntry a = old.get(o);
                    AppEntry b = next.get(n);
                    return a.label.equals(b.label)
                            && a.colorOverride == b.colorOverride
                            && selSnapshot.contains(a.pkg) == selSnapshot.contains(b.pkg);
                }
            });
            main.post(() -> {
                items.clear();
                items.addAll(next);
                diff.dispatchUpdatesTo(AppListAdapter.this);
            });
        });
    }

    void refresh(String pkg)
    {
        for (int i = 0; i < items.size(); i++)
        {
            if (items.get(i).pkg.equals(pkg))
            {
                notifyItemChanged(i);
                return;
            }
        }
    }

    void shutdown()
    {
        io.shutdownNow();
    }

    @Override
    public int getItemCount()
    {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
    {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_app_row, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position)
    {
        final AppEntry e = items.get(position);
        boolean sel = selected.contains(e.pkg);

        h.name.setText(e.label);
        h.pkg.setText(e.pkg);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(ctx, R.color.hc_surface));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(sel ? 3 : 1), ContextCompat.getColor(ctx,
                sel ? R.color.hc_accent : R.color.hc_border));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(16));
        h.itemView.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), bg, mask));

        h.sw.setOnCheckedChangeListener(null);
        h.sw.setChecked(sel);
        h.sw.setContentDescription(e.label);
        h.sw.setOnCheckedChangeListener((b, checked) -> listener.onToggle(e, checked));

        h.itemView.setOnClickListener(v -> h.sw.toggle());
        h.itemView.setOnLongClickListener(v -> {
            listener.onLongPress(e, h.getBindingAdapterPosition());
            return true;
        });

        if (e.colorOverride != Color.TRANSPARENT)
        {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(e.colorOverride);
            dot.setStroke(dp(1), ContextCompat.getColor(ctx, R.color.hc_text));
            h.dot.setBackground(dot);
            h.dot.setVisibility(View.VISIBLE);
        }
        else
        {
            h.dot.setVisibility(View.GONE);
        }

        h.icon.setTag(e.pkg);
        Drawable cached = icons.get(e.pkg);
        if (cached != null)
        {
            h.icon.setImageDrawable(cached);
        }
        else
        {
            h.icon.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.bg_icon_placeholder));
            final String pkg = e.pkg;
            io.execute(() -> {
                Drawable d = null;
                try { d = pm.getApplicationIcon(pkg); }
                catch (Exception ignored) {}
                final Drawable loaded = d;
                main.post(() -> {
                    if (loaded != null)
                    {
                        icons.put(pkg, loaded);
                        if (pkg.equals(h.icon.getTag()))
                        {
                            h.icon.setImageDrawable(loaded);
                        }
                    }
                });
            });
        }
    }

    private int dp(int v)
    {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    static class Holder extends RecyclerView.ViewHolder
    {
        final ImageView icon;
        final TextView name;
        final TextView pkg;
        final View dot;
        final SwitchCompat sw;

        Holder(View v)
        {
            super(v);
            icon = v.findViewById(R.id.app_icon);
            name = v.findViewById(R.id.app_name);
            pkg = v.findViewById(R.id.app_pkg);
            dot = v.findViewById(R.id.color_dot);
            sw = v.findViewById(R.id.app_switch);
        }
    }
}
