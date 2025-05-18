package com.zoffcc.applications.aod;

import android.content.pm.PackageManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.HashMap;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.aod.MainActivity.pm;

public class CustomListAdapter extends RecyclerView.Adapter<CustomListAdapter.MyViewHolder>
{
    private static final String TAG = "D7:Adaptr";
    public final static HashMap<Integer, noti_logo_item> logo_list = new HashMap<>();

    public static class noti_logo_item
    {
        private final String pkgname;
        private final int id;

        public noti_logo_item(String pkgname, int id)
        {
            this.pkgname = pkgname;
            this.id = id;
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
            @SuppressWarnings("SuspiciousMethodCalls")
            noti_logo_item item = logo_list.get(logo_list.keySet().toArray()[position]);
            try
            {
                if (item != null)
                {
                    holder.logo_imageview.setImageDrawable(pm.getApplicationIcon(item.pkgname));
                }
                else
                {
                    holder.logo_imageview.setImageDrawable(null);
                }
            }
            catch (Exception e)
            {
                holder.logo_imageview.setImageDrawable(null);
                e.printStackTrace();
            }
        }
        catch(Exception e1)
        {
            e1.printStackTrace();
        }
    }

    @Override
    public int getItemCount()
    {
        return logo_list.size();
    }

    static class MyViewHolder extends RecyclerView.ViewHolder
    {
        ImageView logo_imageview;

        public MyViewHolder(@NonNull View itemView)
        {
            super(itemView);
            logo_imageview = itemView.findViewById(R.id.logo_imageview);
        }
    }
}
