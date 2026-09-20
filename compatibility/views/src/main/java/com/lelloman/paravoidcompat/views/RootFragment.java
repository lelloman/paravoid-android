package com.lelloman.paravoidcompat.views;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.lelloman.paravoidcompat.views.databinding.FragmentRootBinding;

public final class RootFragment extends Fragment {
    FragmentRootBinding binding;
    ProbeModel model;
    String modelBeforeViewRestore;
    @Override public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle state) {
        binding = FragmentRootBinding.inflate(inflater, parent, false);
        return binding.getRoot();
    }
    @Override public void onViewCreated(View view, Bundle state) {
        model = new ViewModelProvider(this).get(ProbeModel.class);
        modelBeforeViewRestore = model.text.getValue();
        binding.setLifecycleOwner(getViewLifecycleOwner());
        binding.setModel(model);
        binding.executePendingBindings();
    }
    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}
