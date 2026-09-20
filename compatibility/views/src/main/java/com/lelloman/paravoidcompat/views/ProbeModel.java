package com.lelloman.paravoidcompat.views;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;

public final class ProbeModel extends ViewModel {
    public final String instance = java.util.UUID.randomUUID().toString();
    public final MutableLiveData<String> text;
    public ProbeModel(SavedStateHandle state) { text = state.getLiveData("text", "initial"); }
}
