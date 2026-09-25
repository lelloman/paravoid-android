package com.lelloman.paravoidandroid.runtime;

import android.app.Application;
import android.content.Context;
import android.os.*;
import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import com.lelloman.paravoidandroid.delivery.*;
import com.lelloman.paravoidandroid.runtime.lifecycle.RuntimeLifecycle;
import com.lelloman.paravoidandroid.updates.*;
import com.lelloman.paravoidandroid.recovery.RecoveryUpdateProvider;
import java.io.*;
import java.util.concurrent.*;

/** Initialized only in the shell update process; never touches payload startup. */
final class UpdateRuntime {
    static final String SUFFIX=":paravoid_updates";
    private static CompletableFuture<UpdateEngine> instance;
    private static ShellPolicy installedPolicy;
    private static DeliveryClient delivery;
    private static PushConnection socket;
    private static boolean appVisible;
    static synchronized void visible(Context context,boolean visible) {
        appVisible=visible;
        if(!visible) { if(socket!=null) socket.stop(); socket=null; return; }
        engine(context).thenAccept(engine-> {
            synchronized(UpdateRuntime.class) {
                if(!appVisible || socket!=null || installedPolicy==null || !Boolean.parseBoolean(installedPolicy.updates.get("pushEnabled"))) return;
                String url=installedPolicy.updates.getOrDefault("pushWebSocketUrl","");
                if(url.isEmpty()) return;
                try { socket=connection(context,installedPolicy,engine,new WebSocketPushTransport(),url); socket.start(); }
                catch(Exception failure) { socket=null; }
            }
        });
    }
    private static PushConnection connection(Context app,ShellPolicy policy,UpdateEngine engine,PushTransport transport,String endpoint) throws Exception {
        File directory=new File(app.getNoBackupFilesDir(),"paravoid-push-v1");
        if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Push directory unavailable");
        PushRequest request=new PushRequest(policy.applicationId,policy.shellContractId,policy.channel,endpoint,directory,delivery.pushHeaders(endpoint));
        return new PushConnection(transport,request,provider(policy.updates.get("pushAuthenticationClass"),PushAuthentication.class),engine);
    }
    static synchronized CompletableFuture<UpdateEngine> engine(Context context) {
        if(instance!=null) return instance;
        Context app=context.getApplicationContext();
        if(!Application.getProcessName().equals(app.getPackageName()+SUFFIX)) throw new IllegalStateException("Wrong update owner process");
        ExecutorService worker=Executors.newSingleThreadExecutor();
        instance=CompletableFuture.supplyAsync(()-> {
            try {
                AndroidLifecycleEnvironment env=new AndroidLifecycleEnvironment(app);
                ApkInstalledStateSource source=new ApkInstalledStateSource(env,new SignedMetadataVerifier());
                ShellPolicy policy=source.read().policy;
                if(!policy.updatesEnabled) throw new IOException("Updates disabled");
                RequestScope scope=env.scope(policy);
                RuntimeLifecycle lifecycle=new RuntimeLifecycle(new File(app.getNoBackupFilesDir(),"paravoid-v1"),policy,scope,
                    new CompleteVpkVerifier(),env,false,source);
                lifecycle.openOrInitialize();
                DeliveryClient client=new DeliveryClient(policy,new SignedMetadataVerifier(),lifecycle,env,new File(app.getNoBackupFilesDir(),"paravoid-delivery/shared-v1"));
                client.installedApk(env.currentBaseApk());
                UpdateChecker checker=provider(policy.updates.get("checkerClass"),UpdateChecker.class);
                UpdateUpdater updater=provider(policy.updates.get("updaterClass"),UpdateUpdater.class);
                UpdatePolicy hook=provider(policy.updates.get("policyClass"),UpdatePolicy.class);
                client.providers(checker,updater);
                Bundle metadata=app.getPackageManager().getApplicationInfo(app.getPackageName(),android.content.pm.PackageManager.GET_META_DATA).metaData;
                String legacy=metadata==null ? "" : metadata.getString("paravoid.recoveryProvider","");
                if(!legacy.isEmpty()) client.recoveryProvider(provider(legacy,RecoveryUpdateProvider.class));
                UpdateSchedule defaults=UpdateScheduleCodec.read(policy.updates);
                File old=new File(app.getNoBackupFilesDir(),"paravoid-update-preferences");
                if(old.isFile()) {
                    DeliveryPreferences p=DeliveryPreferences.read(old.toPath());
                    defaults=defaults.preferences(p.automaticChecks,p.automaticDownloads,p.unmeteredOnly);
                }
                // The old retry format did not distinguish check-only and update intent.
                java.nio.file.Files.deleteIfExists(new File(app.getNoBackupFilesDir(),"paravoid-update-preferences.retry").toPath());
                Handler main=new Handler(Looper.getMainLooper());
                UpdateScheduler scheduler=new UpdateScheduler(app,policy);
                UpdateEngine engine=new UpdateEngine(client,lifecycle,scope,env,new File(app.getNoBackupFilesDir(),"paravoid-updates-v1"),
                    worker,main::post,defaults,hook,checker!=null || updater!=null || hook!=null || !legacy.isEmpty(),scheduler::reconcile);
                installedPolicy=policy; delivery=client;
                engine.configurePush(Boolean.parseBoolean(policy.updates.get("pushEnabled")),"prompt".equals(policy.updates.get("updateBehavior")));
                if(Boolean.parseBoolean(policy.updates.get("pushEnabled"))) {
                    try {
                        PushTransport transport=provider(policy.updates.get("pushTransportClass"),PushTransport.class);
                        if(transport!=null) connection(app,policy,engine,transport,"").start();
                    } catch(Exception | LinkageError unavailable) {
                        android.util.Log.e("ParavoidAndroid","Push registration unavailable");
                    }
                }
                engine.listen(new UpdatePrompts(app,engine));
                return engine;
            } catch(Exception failure) { throw new CompletionException(failure); }
        },worker);
        return instance;
    }
    private static <T> T provider(String name,Class<T> api) throws Exception {
        return name==null || name.isEmpty() ? null : api.cast(Class.forName(name,true,UpdateRuntime.class.getClassLoader()).getConstructor().newInstance());
    }
}
