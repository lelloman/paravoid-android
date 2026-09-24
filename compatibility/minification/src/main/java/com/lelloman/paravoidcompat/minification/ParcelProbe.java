package com.lelloman.paravoidcompat.minification;

import android.os.Parcel;
import android.os.Parcelable;

public final class ParcelProbe implements Parcelable {
    public final int value;
    public ParcelProbe(int value) { this.value = value; }
    private ParcelProbe(Parcel source) { value = source.readInt(); }
    @Override public void writeToParcel(Parcel target, int flags) { target.writeInt(value); }
    @Override public int describeContents() { return 0; }
    public static final Parcelable.Creator<ParcelProbe> CREATOR = new Parcelable.Creator<ParcelProbe>() {
        @Override public ParcelProbe createFromParcel(Parcel source) { return new ParcelProbe(source); }
        @Override public ParcelProbe[] newArray(int size) { return new ParcelProbe[size]; }
    };
}
