package com.zoffcc.applications.aod;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * One actionable notification (or a synthetic battery / pending-alarm
 * event) that the notification listener hands over to the overlay renderer.
 *
 * It carries no message content at all: only which app posted, the system
 * key, when, the light color and the counter the app itself publishes.
 * There is deliberately no field for a title, a body or any other text, so
 * nothing personal can be parcelled, cached or logged through this class.
 */
public final class NotificationItem implements Parcelable
{
    public String pkg = "";
    public String key = "";
    public int color = Prefs.DEFAULT_COLOR;
    public long postTime = 0L;
    public boolean batteryEvent = false;
    /** Synthetic pending-alarm event, read from the system alarm clock
     *  (AlarmManager.getNextAlarmClock): metadata only - trigger time and
     *  the package that set the alarm. Unlike a battery event it carries
     *  the setting app's package, so the icons style draws that app's icon,
     *  but it never adds to any notification count. */
    public boolean alarmEvent = false;
    /** Notification.number: what the app says this notification stands for
     *  (e.g. unread messages). 0 when the app does not publish a counter. */
    public int number = 0;
    /** Resource id of the small status-bar symbol the app posted, in that
     *  app's own resources. 0 when it has none, or when it posted a bitmap or
     *  URI icon instead - those are never loaded. A number, not content. */
    public int iconRes = 0;
    /** Notification.color: the accent the app chose for its notification
     *  icon, or 0 when it did not choose one. */
    public int accent = 0;

    public NotificationItem() {}

    protected NotificationItem(Parcel in)
    {
        pkg = in.readString();
        key = in.readString();
        color = in.readInt();
        postTime = in.readLong();
        batteryEvent = in.readByte() != 0;
        number = in.readInt();
        iconRes = in.readInt();
        accent = in.readInt();
        alarmEvent = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags)
    {
        dest.writeString(pkg);
        dest.writeString(key);
        dest.writeInt(color);
        dest.writeLong(postTime);
        dest.writeByte((byte) (batteryEvent ? 1 : 0));
        dest.writeInt(number);
        dest.writeInt(iconRes);
        dest.writeInt(accent);
        dest.writeByte((byte) (alarmEvent ? 1 : 0));
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
