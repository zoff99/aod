package com.zoffcc.applications.aod;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.ArrayList;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Horizontal strip of the apps that currently have a pending notification.
 * Long-press an icon to assign a per-app light color.
 */
public class CustomListAdapter extends RecyclerView.Adapter<CustomListAdapter.MyViewHolder>
{
    public interface OnItemLongClick
    {
        void onItemLongClick(String pkg);
    }

    public static final ArrayList<noti_logo_item> logo_list = new ArrayList<>();
    public OnItemLongClick longClickListener;

    public static class noti_logo_item
    {
        public final String pkgname;
        public final String key;

        public noti_logo_item(String pkgname, String key)
        {
            this.pkgname = pkgname;
            this.key = key;
        }
    }

    @NonNull
    @Override
    public MyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
    {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.logo_view, parent, false);
        return new MyViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MyViewHolder holder, int position)
    {
        try
        {
            noti_logo_item item = logo_list.get(position);
            holder.logo_imageview.setImageDrawable(
                    holder.itemView.getContext().getPackageManager().getApplicationIcon(item.pkgname));
            holder.itemView.setOnLongClickListener(v -> {
                if (longClickListener != null)
                {
                    longClickListener.onItemLongClick(item.pkgname);
                    return true;
                }
                return false;
            });
        }
        catch (Exception e)
        {
            holder.logo_imageview.setImageDrawable(null);
        }
    }

    @Override
    public int getItemCount()
    {
        return logo_list.size();
    }

    static class MyViewHolder extends RecyclerView.ViewHolder
    {
        final ImageView logo_imageview;

        public MyViewHolder(@NonNull View itemView)
        {
            super(itemView);
            logo_imageview = itemView.findViewById(R.id.logo_imageview);
        }
    }
}
