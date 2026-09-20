package com.lelloman.paravoidcompat.views;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.fragment.app.Fragment;

public final class DetailFragment extends Fragment {
    @Override public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle state) {
        TextView view = new TextView(requireContext());
        Token token = requireArguments().getParcelable("token");
        view.setText("detail:" + token.value);
        return view;
    }
    public static final class Token implements Parcelable {
        public final String value;
        public Token(String value) { this.value = value; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel out, int flags) { out.writeString(value); }
        public static final Creator<Token> CREATOR = new Creator<Token>() {
            public Token createFromParcel(Parcel in) { return new Token(in.readString()); }
            public Token[] newArray(int size) { return new Token[size]; }
        };
    }
}
