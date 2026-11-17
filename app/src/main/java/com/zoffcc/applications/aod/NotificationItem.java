package com.zoffcc.applications.aod;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * One actionable notification (or a synthetic battery event) that the
 * notification listener hands over to the overlay renderer.
 */
public final class NotificationItem implements Parcelable
{
    public String pkg = "";
    public String key = "";
    public String title = "";
    public String text = "";
    public int color = Prefs.DEFAULT_COLOR;
    public long postTime = 0L;
    public boolean batteryEvent = false;

    public NotificationItem() {}

    protected NotificationItem(Parcel in)
    {
        pkg = in.readString();
        key = in.readString();
        title = in.readString();
        text = in.readString();
        color = in.readInt();
        postTime = in.readLong();
        batteryEvent = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags)
    {
        dest.writeString(pkg);
        dest.writeString(key);
        dest.writeString(title);
        dest.writeString(text);
        dest.writeInt(color);
        dest.writeLong(postTime);
        dest.writeByte((byte) (batteryEvent ? 1 : 0));
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<NotificationItem> CREATOR = new Creator<NotificationItem>()
    {
        @Override
        public NotificationItem createFromParcel(Parcel in) { return new NotificationItem(in); }

        @Override
        public NotificationItem[] newArray(int size) { return new NotificationItem[size]; }
    };
}
