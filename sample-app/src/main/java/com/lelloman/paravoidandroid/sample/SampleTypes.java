package com.lelloman.paravoidandroid.sample;

import android.os.Parcel;
import android.os.Parcelable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Small class-shape fixtures executed by the UI and device tests. */
public final class SampleTypes {
    private SampleTypes() {}
    public enum Mode { READY }
    public interface Mapper<T> { T map(T value); }
    @Retention(RetentionPolicy.RUNTIME) public @interface Label { String value(); }
    public static final class Box<T> {
        private final T value;
        public Box(T value) { this.value = value; }
        public T value() { return value; }
    }
    @Label("runtime-annotation")
    public static final class Reflected {
        public String message() { return "reflection-ok"; }
    }
    public static final class SavedCounter implements Parcelable {
        public final int value;
        public SavedCounter(int value) { this.value = value; }
        private SavedCounter(Parcel parcel) { value = parcel.readInt(); }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel parcel, int flags) { parcel.writeInt(value); }
        public static final Creator<SavedCounter> CREATOR = new Creator<SavedCounter>() {
            @Override public SavedCounter createFromParcel(Parcel parcel) { return new SavedCounter(parcel); }
            @Override public SavedCounter[] newArray(int size) { return new SavedCounter[size]; }
        };
    }
}
