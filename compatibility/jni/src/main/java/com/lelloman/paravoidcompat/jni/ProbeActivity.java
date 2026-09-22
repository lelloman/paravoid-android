package com.lelloman.paravoidcompat.jni;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.widget.TextView;
import com.lelloman.paravoidcompat.jni.bridge.NativeBridge;
import dalvik.system.InMemoryDexClassLoader;
import java.nio.ByteBuffer;
import org.json.JSONObject;

public final class ProbeActivity extends Activity {
    private String run;
    private interface Probe { void run() throws Exception; }
    private static void require(boolean value) { if (!value) throw new AssertionError("Unexpected native result"); }
    private static void check(JSONObject results, String name, Probe probe) throws Exception {
        try { probe.run(); results.put(name, "PASS"); }
        catch (Exception | LinkageError | AssertionError error) {
            android.util.Log.e("JniProbe", name, error);
            results.put(name, error.toString());
        }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        run = state != null ? state.getString("run") : getIntent().getStringExtra("probeRun");
        TextView view = new TextView(this);
        view.setText("Running JNI probes");
        setContentView(view);
        new Thread(() -> {
            try {
                JSONObject results = new JSONObject();
                check(results, "native.providerStartup", () -> require("PASS".equals(ProbeProvider.result)));
                check(results, "native.applicationStartup", () -> require("PASS".equals(ProbeApplication.result)));
                check(results, "native.load", () -> { NativeBridge.loadAgain(); NativeBridge.loadAgain(); });
                check(results, "native.registerNativesAndDependency", () -> require(NativeBridge.registered(33) == 42));
                check(results, "native.onLoadOnce", () -> require(NativeBridge.onLoadCount() == 1));
                check(results, "native.findClassCallback", () -> require(NativeBridge.callback() == 42));
                // Native-created thread: expected raw FindClass failure, cached class and explicit loader success.
                check(results, "native.attachedThread", () -> require(NativeBridge.nativeThread() == 7));
                check(results, "native.directBuffer", () -> {
                    ByteBuffer data = ByteBuffer.allocateDirect(4);
                    data.put(new byte[] {1, 2, 3, 4});
                    require(NativeBridge.mutate(data) == 10);
                    require(data.get(0) == 2 && data.get(3) == 5);
                });
                check(results, "native.dlopen", () -> require(NativeBridge.dynamicLibrary() == 73));
                check(results, "native.abiAndCppRuntime", () -> require(
                    java.util.Arrays.asList(android.os.Build.SUPPORTED_ABIS).contains(NativeBridge.abi())));
                check(results, "native.exception", () -> {
                    try { NativeBridge.throwFromNative(); throw new AssertionError("No exception"); }
                    catch (IllegalArgumentException expected) { require("native failure".equals(expected.getMessage())); }
                });
                check(results, "packaging.extraction", () -> require(
                    ((getApplicationInfo().flags & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0)
                        == getPackageName().contains(".extracted.")));
                check(results, "loader.isolation", () -> {
                    ClassLoader loader = getClass().getClassLoader();
                    boolean shell = getPackageName().endsWith(".paravoid");
                    require((loader instanceof InMemoryDexClassLoader) == shell);
                    require(NativeBridge.class.getClassLoader() == loader);
                    if (shell) {
                        try { loader.getParent().loadClass(NativeBridge.class.getName()); throw new AssertionError("Bridge leaked"); }
                        catch (ClassNotFoundException expected) { /* Library's Java code stays in payload. */ }
                    }
                });
                JSONObject report = new JSONObject().put("run", run).put("pid", android.os.Process.myPid())
                    .put("process64", android.os.Process.is64Bit()).put("abi", NativeBridge.abi())
                    .put("nativeMaps", java.nio.file.Files.readAllLines(java.nio.file.Paths.get("/proc/self/maps")).stream()
                        .filter(line -> line.contains("libprobe_jni.so")).collect(java.util.stream.Collectors.joining("\n")))
                    .put("restored", state != null).put("results", results);
                getSharedPreferences("jni-probe", MODE_PRIVATE).edit().putString("report", report.toString()).commit();
                runOnUiThread(() -> view.setText(results.toString()));
            } catch (Exception e) { throw new IllegalStateException(e); }
        }, "jni-probe").start();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("run", run);
        super.onSaveInstanceState(state);
        getSharedPreferences("jni-probe", MODE_PRIVATE).edit().putInt("savedPid", android.os.Process.myPid()).commit();
    }
}
