package com.lelloman.paravoidcompat.os;

import android.os.Parcel;
import android.os.Parcelable;

/** Exists only in the target's payload, not in the peer or System UI. */
public final class ProbeParcel implements Parcelable {
    public final String value;
    public ProbeParcel(String value) { this.value = value; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel destination, int flags) { destination.writeString(value); }
    public static final Creator<ProbeParcel> CREATOR = new Creator<ProbeParcel>() {
        @Override public ProbeParcel createFromParcel(Parcel source) { return new ProbeParcel(source.readString()); }
        @Override public ProbeParcel[] newArray(int size) { return new ProbeParcel[size]; }
    };
}
