package com.lelloman.paravoidcompat.os.contract;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.Button;
import com.lelloman.paravoidcompat.os.contract.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

public final class BinderClient implements ServiceConnection {
    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final String run;
    private boolean bound;
    private int generation;
    private IBinder current;
    private IBinder.DeathRecipient deathRecipient;
    public BinderClient(Activity activity) {
        this.activity = activity;
        run = activity.getIntent().getStringExtra("run");
    }
    public void start() {
        Button button = new Button(activity);
        button.setText("Unbind service");
        activity.setContentView(button);
        button.setOnClickListener(view -> {
            if (bound) { unbind(); button.setText("Bind service"); }
            else { bind(); button.setText("Unbind service"); }
        });
        bind();
    }
    private void bind() {
        bound = activity.bindService(new Intent().setClassName(activity.getIntent().getStringExtra("target"),
            activity.getIntent().getBooleanExtra("remoteWorker", false)
                ? "com.lelloman.paravoidcompat.os.WorkerProbeService"
                : "com.lelloman.paravoidcompat.os.BinderProbeService")
            .putExtra("bindingParcel", new WireMessage(run, 0, 0, "binding", false)), this, Context.BIND_AUTO_CREATE);
        if (!bound) error(new IllegalStateException("bindService returned false"));
    }
    private void unbind() {
        if (current != null && deathRecipient != null) current.unlinkToDeath(deathRecipient, 0);
        activity.unbindService(this); bound = false;
    }
    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        current = binder;
        int phase = ++generation;
        IProbe api = IProbe.Stub.asInterface(binder);
        deathRecipient = () -> {
            boolean deadCall = false;
            try { api.reject(); } catch (RemoteException expected) { deadCall = true; }
            catch (Exception unexpected) { error(unexpected); }
            prefs().edit().putString("binderDeathRun", run)
                .putBoolean("binderDeadCall", deadCall).putBoolean("binderDead", !binder.isBinderAlive()).commit();
        };
        try { binder.linkToDeath(deathRecipient, 0); }
        catch (RemoteException failure) { error(failure); return; }
        worker.execute(() -> {
            try {
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<WireMessage> received = new AtomicReference<>();
                IProbeCallback callback = new IProbeCallback.Stub() {
                    @Override public void received(WireMessage response) { received.set(response); latch.countDown(); }
                };
                WireMessage request = new WireMessage(run, 0, 0, "request", false);
                WireMessage response = api.exchange(request, callback);
                boolean delivered = latch.await(5, TimeUnit.SECONDS);
                List<WireMessage> list = api.echoList(Arrays.asList(request, null, new WireMessage("尾", 0, 0, "last", false)));
                boolean rejected = false;
                try { api.reject(); } catch (IllegalArgumentException expected) { rejected = "probe-rejected".equals(expected.getMessage()); }
                WireMessage reply = received.get();
                Bundle nested = new Bundle();
                nested.putParcelable("value", request);
                Bundle input = new Bundle();
                input.putBundle("nested", nested);
                String rawStatus = api.exchangeBundle(input, false).getString("status");
                Bundle prepared = api.exchangeBundle(input, true);
                prepared.setClassLoader(WireMessage.class.getClassLoader());
                Bundle preparedNested = prepared.getBundle("nested");
                if (preparedNested == null) throw new IllegalStateException("Prepared Bundle failed: " + prepared.getString("status"));
                preparedNested.setClassLoader(WireMessage.class.getClassLoader());
                WireMessage bundleValue = preparedNested.getParcelable("value");
                JSONObject result = new JSONObject().put("run", run).put("pid", response.pid)
                    .put("peerPid", android.os.Process.myPid()).put("instance", response.instance)
                    .put("remote", binder.queryLocalInterface(IProbe.DESCRIPTOR) == null)
                    .put("uid", response.callerUid == android.os.Process.myUid())
                    .put("loader", response.loader)
                    .put("clientLoader", WireMessage.class.getClassLoader() == activity.getClass().getClassLoader()
                        && IProbe.class.getClassLoader() == activity.getClass().getClassLoader()
                        && callback.getClass().getClassLoader() == activity.getClass().getClassLoader()
                        && (activity.getClass().getClassLoader() instanceof dalvik.system.InMemoryDexClassLoader)
                            == activity.getPackageName().endsWith(".paravoid"))
                    .put("text", ("echo-λ-" + run).equals(response.text))
                    .put("callback", delivered && reply != null && reply.text.equals(response.text)
                        && reply.instance.equals(response.instance) && reply.pid == response.pid)
                    .put("list", list.size() == 3 && run.equals(list.get(0).text) && list.get(1) == null && "尾".equals(list.get(2).text))
                    .put("null", api.exchange(null, callback) == null && api.echoList(null) == null)
                    .put("exception", rejected)
                    .put("bundleRawRejected", rawStatus != null && rawStatus.contains("BadParcelableException")
                        && rawStatus.contains("ClassNotFoundException"))
                    .put("bundlePrepared", "PASS".equals(prepared.getString("status")) && bundleValue != null
                        && ("bundle-λ-" + run).equals(bundleValue.text) && bundleValue.loader
                        && bundleValue.pid == response.pid && bundleValue.instance.equals(response.instance)
                        && bundleValue.callerUid == android.os.Process.myUid());
                prefs().edit().putString("binder" + phase, result.toString()).commit();
            } catch (Exception failure) { error(failure); }
        });
    }
    @Override public void onServiceDisconnected(ComponentName name) {
        prefs().edit().putString("binderDisconnected", run).commit();
    }
    @Override public void onNullBinding(ComponentName name) { error(new IllegalStateException("null binding")); }
    @Override public void onBindingDied(ComponentName name) { error(new IllegalStateException("binding died")); }
    private SharedPreferences prefs() { return activity.getSharedPreferences("os-probe", Context.MODE_PRIVATE); }
    private void error(Exception failure) { prefs().edit().putString("binderError", failure.toString()).commit(); }
    public void close() { if (bound) unbind(); worker.shutdownNow(); }
}
