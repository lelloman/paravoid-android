package com.lelloman.paravoidcompat.views;

import android.widget.TextView;
import androidx.databinding.BindingAdapter;

public final class Adapters {
    @BindingAdapter("probeText")
    public static void setProbeText(TextView view, String value) { view.setText("adapted:" + value); }
}
