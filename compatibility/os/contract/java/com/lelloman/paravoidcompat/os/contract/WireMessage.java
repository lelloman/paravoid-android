package com.lelloman.paravoidcompat.os.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Shared wire schema, compiled independently into each APK (target payload). */
public final class WireMessage implements Parcelable {
    public final String text;
    public final int pid;
    public final int callerUid;
    public final String instance;
    public final boolean loader;
    public WireMessage(String text, int pid, int callerUid, String instance, boolean loader) {
        this.text = text; this.pid = pid; this.callerUid = callerUid;
        this.instance = instance; this.loader = loader;
    }
    private WireMessage(Parcel in) {
        this(in.readString(), in.readInt(), in.readInt(), in.readString(), in.readInt() != 0);
    }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(text); out.writeInt(pid); out.writeInt(callerUid);
        out.writeString(instance); out.writeInt(loader ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<WireMessage> CREATOR = new Creator<WireMessage>() {
        @Override public WireMessage createFromParcel(Parcel in) { return new WireMessage(in); }
        @Override public WireMessage[] newArray(int size) { return new WireMessage[size]; }
    };
}
