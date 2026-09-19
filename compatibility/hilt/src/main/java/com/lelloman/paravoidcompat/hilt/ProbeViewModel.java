package com.lelloman.paravoidcompat.hilt;

import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import dagger.hilt.android.lifecycle.HiltViewModel;
import javax.inject.Inject;

@HiltViewModel
public final class ProbeViewModel extends ViewModel {
    public final ProbeService service;
    public final SavedStateHandle state;

    @Inject public ProbeViewModel(ProbeService service, SavedStateHandle state) {
        this.service = service;
        this.state = state;
    }
}
