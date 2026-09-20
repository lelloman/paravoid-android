package com.lelloman.paravoidcompat.views;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.databinding.DataBindingUtil;
import com.lelloman.paravoidcompat.views.databinding.ActivityProbeBinding;
import dalvik.system.InMemoryDexClassLoader;
import org.json.JSONObject;

public final class ProbeActivity extends AppCompatActivity {
    private String run;
    private int phase;
    private int generation;
    private boolean restored;
    private ActivityProbeBinding binding;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        restored = state != null;
        run = restored ? state.getString("run") : getIntent().getStringExtra("probeRun");
        phase = restored ? state.getInt("phase") : 0;
        generation = restored ? state.getInt("generation") + 1 : 0;
        binding = ActivityProbeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.advance.setOnClickListener(v -> {
            if (phase != 0) throw new IllegalStateException("Advance only once");
            RootFragment root = root();
            root.binding.editor.setText("edited");
            root.binding.custom.setValue(41);
            Bundle args = new Bundle();
            args.putParcelable("token", new DetailFragment.Token(run));
            getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true).add(R.id.detail, DetailFragment.class, args, "detail")
                .addToBackStack("detail").commit();
            getSupportFragmentManager().executePendingTransactions();
            phase = 1;
            scheduleReport();
        });
        binding.recreate.setOnClickListener(v -> recreate());
        binding.back.setOnClickListener(v -> {
            if (!getSupportFragmentManager().popBackStackImmediate()) throw new IllegalStateException("No back stack");
            phase = 2;
            scheduleReport();
        });
    }
    private RootFragment root() { return (RootFragment) getSupportFragmentManager().findFragmentByTag("root"); }
    @Override protected void onResume() { super.onResume(); scheduleReport(); }
    private void scheduleReport() { binding.getRoot().postDelayed(this::report, 250); }
    private void report() {
        if (isFinishing() || isDestroyed()) return;
        try {
            RootFragment root = root();
            root.binding.executePendingBindings();
            String expected = phase == 0 ? "initial" : "edited";
            JSONObject results = new JSONObject();
            check(results, "viewBinding", binding.rootFragment == findViewById(R.id.root_fragment));
            check(results, "xml.fragment", root.isAdded() && root.getView() != null);
            check(results, "appcompat.inflater", root.binding.editor instanceof AppCompatEditText);
            check(results, "xml.attributes", root.binding.custom.xmlSeed == 5);
            check(results, "customView.savedState", root.binding.custom.value == (phase == 0 ? 5 : 41));
            check(results, "dataBinding.mapper", DataBindingUtil.getBinding(root.requireView()) == root.binding);
            check(results, "dataBinding.twoWay", expected.equals(root.model.text.getValue()));
            check(results, "dataBinding.adapter", ("adapted:" + expected).contentEquals(root.binding.echo.getText()));
            check(results, "dataBinding.editor", expected.contentEquals(root.binding.editor.getText()));
            DetailFragment detail = (DetailFragment) getSupportFragmentManager().findFragmentByTag("detail");
            check(results, "fragment.backStack", getSupportFragmentManager().getBackStackEntryCount() == (phase == 1 ? 1 : 0));
            check(results, "fragment.arguments", phase == 1
                ? detail != null && run.equals(((DetailFragment.Token) detail.requireArguments().getParcelable("token")).value)
                : detail == null);
            ClassLoader loader = getClass().getClassLoader();
            boolean shell = getPackageName().endsWith(".paravoid");
            boolean absent = true;
            if (shell) {
                try { loader.getParent().loadClass(RootFragment.class.getName()); absent = false; }
                catch (ClassNotFoundException expectedFailure) { /* Payload isolation. */ }
            }
            check(results, "loader.isolation", (loader instanceof InMemoryDexClassLoader) == shell
                && root.getClass().getClassLoader() == loader
                && root.binding.getClass().getClassLoader() == loader && absent);
            JSONObject report = new JSONObject().put("run", run).put("pid", android.os.Process.myPid())
                .put("phase", phase).put("generation", generation).put("restored", restored).put("results", results);
            getSharedPreferences("views-probe", MODE_PRIVATE).edit().putString("report", report.toString()).commit();
        } catch (Exception e) { throw new IllegalStateException("Probe reporting failed", e); }
    }
    private static void check(JSONObject results, String name, boolean passed) throws Exception {
        results.put(name, passed ? "PASS" : "FAIL");
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("run", run); state.putInt("phase", phase); state.putInt("generation", generation);
        super.onSaveInstanceState(state);
        getSharedPreferences("views-probe", MODE_PRIVATE).edit()
            .putInt("savedPid", android.os.Process.myPid()).putInt("savedGeneration", generation).commit();
    }
}
