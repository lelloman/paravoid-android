package com.lelloman.paravoidcompat.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatTextView;

public final class StatefulView extends AppCompatTextView {
    public int value;
    public final int xmlSeed;
    public StatefulView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.StatefulView);
        xmlSeed = a.getInt(R.styleable.StatefulView_seed, -1);
        a.recycle();
        setValue(xmlSeed);
    }
    public void setValue(int number) { value = number; setText("custom:" + number); }
    @Override public Parcelable onSaveInstanceState() {
        Saved state = new Saved(super.onSaveInstanceState());
        state.value = value;
        return state;
    }
    @Override public void onRestoreInstanceState(Parcelable parcel) {
        Saved state = (Saved) parcel;
        super.onRestoreInstanceState(state.getSuperState());
        setValue(state.value);
    }
    public static final class Saved extends BaseSavedState {
        int value;
        Saved(Parcelable parent) { super(parent); }
        Saved(Parcel source) { super(source); value = source.readInt(); }
        @Override public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags); out.writeInt(value);
        }
        public static final Parcelable.Creator<Saved> CREATOR = new Parcelable.Creator<Saved>() {
            public Saved createFromParcel(Parcel in) { return new Saved(in); }
            public Saved[] newArray(int size) { return new Saved[size]; }
        };
    }
}
