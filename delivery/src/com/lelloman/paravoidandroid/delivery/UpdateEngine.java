package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import com.lelloman.paravoidandroid.updates.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Single owner of update intent. Android supplies scheduling, not a second retry loop. */
public final class UpdateEngine implements UpdateControl {
    public enum Kind { NONE, CHECK, UPDATE }
    public static final class Work {
        public final UpdateSchedule schedule;
        public final Kind kind;
        public final boolean explicit;
        public final long dueSeconds, nextCheckSeconds;
        Work(UpdateSchedule schedule, Kind kind, boolean explicit, long due, long nextCheck) {
            this.schedule=schedule; this.kind=kind; this.explicit=explicit; dueSeconds=due; nextCheckSeconds=nextCheck;
        }
        /** Keep the next check armed even when a periodic wakeup arrives before it is due. */
        public Long checkDeadlineSeconds() {
            if(kind==Kind.CHECK) return dueSeconds;
            return kind==Kind.NONE && schedule.checks ? nextCheckSeconds : null;
        }
    }
    private final DeliveryClient client;
    private final Lifecycle lifecycle;
    private final RequestScope scope;
    private final DeliveryClient.Clock clock;
    private final Path file;
    private final ExecutorService worker;
    private final Executor callbacks;
    private final Consumer<Work> scheduler;
    private final UpdatePolicy policy;
    private final Set<DeliveryController.Listener> listeners=new CopyOnWriteArraySet<>();
    private final AtomicLong cancellation=new AtomicLong();
    private volatile long runningJob;
    private volatile boolean stopped;
    private volatile boolean staging;
    private volatile DeliveryController.Snapshot snapshot;
    /** Effective schedule: installed policy defaults, then a runtime schedule, then user preferences. */
    private volatile UpdateSchedule schedule;
    private volatile UpdateSchedule requestedSchedule;
    // Only explicit choices are durable. Policy defaults are re-read on every start, so an
    // updated shell's schedule reaches existing installs unless an override was chosen.
    private final UpdateSchedule defaults;
    private volatile UpdateSchedule runtimeSchedule;
    private volatile DeliveryPreferences userPreferences;
    private Kind kind=Kind.NONE, lastKind=Kind.CHECK;
    private volatile boolean explicit;
    private boolean lastExplicit;
    private ExpectedArchive target, lastTarget, available;
    private String error;
    private long due, nextCheck, lastCheck, bytes, total, lastProgressNanos;
    private int attempts;
    private DeliveryController.Activity phase=DeliveryController.Activity.IDLE;
    private String partition;
    private final boolean customProvider;
    private boolean pushEnabled, promptUpdates;
    private ExpectedArchive declined;
    private long lastPush;
    private boolean localHintPending;
    private long lastLocalCheck;
    private final LinkedHashSet<String> pushEvents=new LinkedHashSet<>();
    /** APK-pinned configuration, called before accepting transport events. */
    public void configurePush(boolean enabled, boolean prompt) {
        worker.execute(()-> { pushEnabled=enabled; promptUpdates=prompt; if(prompt && !explicit && kind==Kind.UPDATE) { kind=Kind.NONE; persist(); } publish(true); });
    }
    public void pushEvent(byte[] event) { pushEvent(event,()->{}); }
    public void pushEvent(byte[] event,Runnable completion) {
        final String id;
        try { id=UpdateEventCodec.verifyScope(event,scope); } catch(ContractException invalid) { callbacks.execute(completion); return; }
        worker.execute(()-> {
            try {
            if(!pushEnabled || !schedule.checks || "PROVIDER_INTERRUPTED".equals(error) || !pushEvents.add(id)) return;
            while(pushEvents.size()>128) pushEvents.remove(pushEvents.iterator().next());
            if(kind==Kind.NONE) {
                kind=Kind.CHECK; explicit=false; target=null; attempts=0;
                due=Math.max(clock.unixSeconds(),lastPush+60); lastPush=due;
            }
            if(persist()) publish(true);
            } finally { callbacks.execute(completion); }
        });
    }
    /** Authenticated local IPC, independent of per-app network push wiring. */
    public void localHint(Consumer<Boolean> completion) {
        worker.execute(()-> {
            if(!schedule.checks || "PROVIDER_INTERRUPTED".equals(error) || phase==DeliveryController.Activity.ERROR) {
                callbacks.execute(()->completion.accept(true)); return;
            }
            localHintPending=true;
            armLocalHint();
            boolean saved=persist();
            if(saved) publish(true);
            callbacks.execute(()->completion.accept(saved));
        });
    }
    private void armLocalHint() {
        if(!localHintPending || !schedule.checks || kind!=Kind.NONE || phase==DeliveryController.Activity.ERROR) return;
        kind=Kind.CHECK; explicit=false; target=null; attempts=0;
        due=Math.max(clock.unixSeconds(),lastLocalCheck==0 ? 0 : lastLocalCheck+60);
    }
    @Override public void dismiss(ExpectedArchive offer) {
        worker.execute(()-> { declined=offer; persist(); publish(false); });
    }

    public UpdateEngine(DeliveryClient client, Lifecycle lifecycle, RequestScope scope, DeliveryClient.Clock clock,
            File directory, ExecutorService worker, Executor callbacks, UpdateSchedule defaults, UpdatePolicy policy,
            boolean customProvider, Consumer<Work> scheduler) throws IOException {
        this.client=client; this.lifecycle=lifecycle; this.scope=scope; this.clock=clock; this.worker=worker;
        this.callbacks=callbacks; this.policy=policy; this.customProvider=customProvider; this.scheduler=scheduler;
        file=directory.toPath().resolve("operations.properties"); this.defaults=Objects.requireNonNull(defaults);
        schedule=defaults;
        Files.createDirectories(file.getParent());
        UpdateSchedule persisted=load();
        schedule=effective(runtimeSchedule,userPreferences);
        // A changed policy or override applies to existing durable intent like setSchedule.
        if(persisted!=null && persisted.intervalSeconds!=schedule.intervalSeconds) nextCheck=lastCheck==0 ? 0 : lastCheck+schedule.intervalSeconds;
        if(!schedule.checks) localHintPending=false;
        if(!explicit && (!schedule.checks || kind==Kind.UPDATE && !schedule.downloads)) kind=Kind.NONE;
        String current=client.credentialPartition();
        boolean samePartition=Objects.equals(partition,current);
        if(!samePartition) { kind=Kind.NONE; available=null; target=null; nextCheck=0; attempts=0; }
        partition=current;
        // Older shells made temporary writer contention terminal. Resume the saved operation
        // with its remaining retry budget, without overriding disabled automatic preferences.
        if(samePartition && !customProvider && phase==DeliveryController.Activity.ERROR && "UNAVAILABLE".equals(error)
                && attempts<=schedule.maxRetries && lastKind!=Kind.NONE
                && (lastExplicit || schedule.checks && (lastKind!=Kind.UPDATE || schedule.downloads))) {
            kind=lastKind; target=lastTarget; explicit=lastExplicit;
            deferRetry("UNAVAILABLE",-1);
        }
        // An interrupted custom provider requires an explicit retry; do not create a crash loop.
        if(customProvider && Files.exists(file.resolveSibling("provider-running"))) {
            kind=Kind.NONE; error="PROVIDER_INTERRUPTED"; phase=DeliveryController.Activity.ERROR;
        } else if(kind!=Kind.NONE) phase=DeliveryController.Activity.WAITING_TO_RETRY;
        client.progress((name,count,size)-> {
            bytes=count; total=size; staging=name.equals("STAGING");
            phase=staging ? DeliveryController.Activity.STAGING : DeliveryController.Activity.DOWNLOADING;
            long now=System.nanoTime();
            if(staging || now-lastProgressNanos>=250_000_000L) { lastProgressNanos=now; publish(false); }
        });
        armLocalHint(); save(); publish(true);
    }
    public DeliveryController.Snapshot current() { return snapshot; }
    @Override public void listen(DeliveryController.Listener listener) {
        listeners.add(listener); callbacks.execute(()-> { if(listeners.contains(listener) && snapshot!=null) listener.changed(snapshot); });
    }
    @Override public void unlisten(DeliveryController.Listener listener) { listeners.remove(listener); }
    @Override public void refreshSnapshot() { worker.execute(()->publish(false)); }
    @Override public void foreground() { worker.execute(()-> { publish(true); }); }
    @Override public void checkNow() { request(Kind.CHECK,null); }
    @Override public void updateNow() { request(Kind.UPDATE,null); }
    @Override public void updateNow(ExpectedArchive offer) { request(Kind.UPDATE,Objects.requireNonNull(offer)); }
    private void request(Kind command, ExpectedArchive offer) {
        final long epoch=cancellation.get();
        worker.execute(()-> {
            if(epoch!=cancellation.get()) return;
            kind=command; target=offer; explicit=true; attempts=0; due=clock.unixSeconds();
            lastKind=kind; lastTarget=target; lastExplicit=true;
            if(persist()) { publish(true); execute(0); }
        });
    }
    @Override public void retry() {
        worker.execute(()-> {
            kind=lastKind; target=lastTarget; explicit=true; attempts=0; due=clock.unixSeconds();
            if(persist()) { publish(true); execute(0); }
        });
    }
    /** Called by JobScheduler. A token associates stop callbacks with this particular job execution. */
    public void runJob(boolean download, long token, Runnable completion) {
        worker.execute(()-> {
            try {
                if(stoppedJobs.remove(token)) return;
                if(kind==Kind.NONE) {
                    if(download || !schedule.checks || clock.unixSeconds()<nextCheck || "PROVIDER_INTERRUPTED".equals(error)) return;
                    kind=Kind.CHECK; explicit=false; target=null; attempts=0; due=clock.unixSeconds();
                }
                if(download!=(kind==Kind.UPDATE) || clock.unixSeconds()<due) return;
                execute(token);
            } finally { callbacks.execute(completion); }
        });
    }
    private final Set<Long> stoppedJobs=ConcurrentHashMap.newKeySet();
    public void stopJob(long token) {
        stoppedJobs.add(token);
        if(runningJob==token && !staging) { stopped=true; client.cancelDownload(); }
    }
    @Override public void cancelDownload() {
        if(staging) return;
        cancellation.incrementAndGet(); client.cancelDownload();
        worker.execute(()-> { if(phase==DeliveryController.Activity.READY) return; kind=Kind.NONE; localHintPending=false; target=null; phase=DeliveryController.Activity.CANCELLED; error=null; persist(); publish(true); });
    }
    /** A user choice of checks/downloads; other schedule fields keep following the installed policy. */
    @Override public void preferences(DeliveryPreferences value) {
        DeliveryPreferences chosen=new DeliveryPreferences(value.automaticChecks,value.automaticDownloads,value.unmeteredOnly);
        request(effective(runtimeSchedule,chosen),()->userPreferences=chosen);
    }
    /** A complete runtime schedule, which also replaces earlier user preferences. */
    @Override public void schedule(UpdateSchedule value) {
        Objects.requireNonNull(value);
        request(value,()-> { runtimeSchedule=value; userPreferences=null; });
    }
    private void request(UpdateSchedule value,Runnable record) {
        requestedSchedule=value;
        if(!explicit && (!value.checks || !value.downloads) && !staging) client.cancelDownload();
        worker.execute(()-> { record.run(); setSchedule(effective(runtimeSchedule,userPreferences)); });
    }
    private UpdateSchedule effective(UpdateSchedule runtime,DeliveryPreferences preferences) {
        UpdateSchedule base=runtime!=null ? runtime : defaults;
        return preferences==null ? base : base.preferences(preferences.automaticChecks,preferences.automaticDownloads,preferences.unmeteredOnly);
    }
    private void setSchedule(UpdateSchedule value) {
        schedule=value;
        if(!value.checks) localHintPending=false;
        nextCheck=lastCheck==0 ? 0 : lastCheck+schedule.intervalSeconds;
        if(!explicit && (!schedule.checks || kind==Kind.UPDATE && !schedule.downloads)) kind=Kind.NONE;
        armLocalHint(); persist(); publish(true);
    }
    @Override public void retainedPrevious(int count) {
        worker.execute(()-> { try { lifecycle.setRetainedPrevious(count); } catch(ContractException failed) { fail(failed.code.name()); } publish(false); });
    }
    @Override public void retryQuarantinedAfterConfirmation(ExpectedArchive offer) {
        worker.execute(()-> { try { lifecycle.retryQuarantined(offer); } catch(ContractException failed) { fail(failed.code.name()); } publish(false); });
    }
    private void execute(long job) {
        long epoch=cancellation.get();
        runningJob=job; stopped=false; staging=false;
        try {
            if(!explicit && (!schedule.checks || kind==Kind.UPDATE && !schedule.downloads)) { kind=Kind.NONE; return; }
            if(customProvider) Files.write(file.resolveSibling("provider-running"),new byte[]{1});
            if(!explicit && policy!=null) {
                UpdatePolicy.Decision decision=Objects.requireNonNull(policy.evaluate(new UpdatePolicy.Context(kind==Kind.UPDATE,
                    clock.unixSeconds(),lastCheck,error,schedule.policyData)));
                if(decision.kind==UpdatePolicy.Decision.Kind.SKIP) {
                    localHintPending=false;
                    if(kind==Kind.CHECK) nextCheck=clock.unixSeconds()+schedule.intervalSeconds;
                    kind=Kind.NONE; phase=DeliveryController.Activity.IDLE; return;
                }
                if(decision.kind==UpdatePolicy.Decision.Kind.DEFER) {
                    due=Math.max(clock.unixSeconds()+30,decision.untilSeconds); phase=DeliveryController.Activity.WAITING_TO_RETRY; return;
                }
            }
            if(attempts>schedule.maxRetries) { fail("RETRY_EXHAUSTED"); return; }
            if(kind==Kind.CHECK && localHintPending) { localHintPending=false; lastLocalCheck=clock.unixSeconds(); }
            attempts++; lastKind=kind; lastTarget=target; lastExplicit=explicit;
            phase=DeliveryController.Activity.CHECKING; error=null; bytes=0; total=0;
            if(kind==Kind.CHECK) { lastCheck=clock.unixSeconds(); nextCheck=lastCheck+schedule.intervalSeconds; }
            if(!persist()) return;
            if(customProvider) Files.write(file.resolveSibling("provider-running"),new byte[]{1});
            publish(true);
            DeliveryClient.Result result=client.check(scope,kind==Kind.UPDATE,explicit,()-> {
                if(stopped || stoppedJobs.contains(job) || epoch!=cancellation.get() || !explicit && requestedSchedule!=null && !requestedSchedule.checks) throw new HttpTransport.Failure("cancelled");
            },()->!stopped && !stoppedJobs.contains(job) && epoch==cancellation.get() && (explicit || requestedSchedule==null || requestedSchedule.checks && requestedSchedule.downloads),target);
            available=result.available;
            if(target!=null && result.stage==null && !target.equals(result.available)) { fail("OFFER_CHANGED"); return; }
            boolean autoDownload=!promptUpdates && !explicit && kind==Kind.CHECK && schedule.downloads && result.available!=null;
            phase=result.stage!=null ? DeliveryController.Activity.READY : available!=null ? DeliveryController.Activity.AVAILABLE : DeliveryController.Activity.IDLE;
            if(result.status==HeadStatus.SHELL_UPDATE_REQUIRED) error="SHELL_UPDATE_REQUIRED";
            else if(result.status==HeadStatus.NO_COMPATIBLE_RELEASE) error="NO_COMPATIBLE_RELEASE";
            kind=autoDownload ? Kind.UPDATE : Kind.NONE; target=null; attempts=0; due=clock.unixSeconds();
        } catch(ContractException failure) {
            if(failure.code==ContractException.Code.UNAVAILABLE && attempts<=schedule.maxRetries)
                deferRetry(failure.code.name(),-1);
            else fail(failure.code.name());
        }
        catch(Exception | LinkageError failure) {
            if(stopped) { due=clock.unixSeconds()+30; phase=DeliveryController.Activity.WAITING_TO_RETRY; attempts=Math.max(0,attempts-1); }
            else if(epoch!=cancellation.get()) { kind=Kind.NONE; phase=DeliveryController.Activity.CANCELLED; error=null; }
            else {
                long retryAfter=-1; boolean network=failure instanceof java.net.SocketException || failure instanceof java.net.SocketTimeoutException
                    || failure instanceof java.net.UnknownHostException || failure instanceof javax.net.ssl.SSLException;
                String code="PROVIDER_ERROR";
                if(failure instanceof HttpTransport.Failure) {
                    HttpTransport.Failure http=(HttpTransport.Failure)failure;
                    code=http.status==401 || http.status==403 ? "CREDENTIAL_UNAVAILABLE" : http.code;
                    network=http.code.equals("network-io") || http.code.equals("truncated-head") || http.code.equals("truncated-archive") || http.status==429 || http.status>=500;
                    retryAfter=http.retryAfterSeconds;
                } else if(failure instanceof IOException) code="IO";
                if(network && attempts<=schedule.maxRetries) {
                    deferRetry(code,retryAfter);
                } else fail(code);
            }
        } finally {
            staging=false; runningJob=0; stoppedJobs.remove(job);
            try { Files.deleteIfExists(file.resolveSibling("provider-running")); } catch(IOException failure) { fail("PREFERENCES_IO"); }
            armLocalHint(); persist(); publish(true);
        }
    }
    private void fail(String code) { kind=Kind.NONE; error=code; phase=DeliveryController.Activity.ERROR; }
    private void deferRetry(String code,long retryAfter) {
        error=code;
        due=clock.unixSeconds()+Math.max(Math.min(3600,retryAfter),
            Math.min(schedule.maxRetrySeconds,schedule.retrySeconds*(1L << Math.min(10,Math.max(0,attempts-1)))));
        phase=DeliveryController.Activity.WAITING_TO_RETRY;
    }
    private boolean persist() {
        try { save(); return true; } catch(IOException failure) { fail("PREFERENCES_IO"); return false; }
    }
    private void publish(boolean scheduling) {
        LifecycleSnapshot life=null;
        try { life=lifecycle.snapshot(); } catch(ContractException failure) { error=failure.code.name(); }
        snapshot=new DeliveryController.Snapshot(life,new DeliveryPreferences(schedule.checks,schedule.downloads,schedule.downloadUnmetered),
            phase,available,error,schedule,bytes,total,lastCheck,kind==Kind.NONE ? nextCheck : due,
            promptUpdates && available!=null && !available.equals(declined) && (phase==DeliveryController.Activity.AVAILABLE || "OFFER_CHANGED".equals(error))
                && (life==null || !available.equals(life.pending) && !available.equals(life.active)));
        DeliveryController.Snapshot value=snapshot;
        Work work=new Work(schedule,kind,explicit,due,nextCheck);
        callbacks.execute(()-> {
            if(scheduling) scheduler.accept(work);
            for(DeliveryController.Listener listener:listeners) if(listeners.contains(listener)) listener.changed(value);
        });
    }
    private void save() throws IOException {
        // The effective schedule is diagnostic only; overrides are the durable choices.
        Properties p=new Properties(); p.putAll(UpdateScheduleCodec.write(schedule));
        if(runtimeSchedule!=null) UpdateScheduleCodec.write(runtimeSchedule).forEach((k,v)->p.setProperty("runtime."+k,v));
        if(userPreferences!=null) {
            p.setProperty("preferences.checks",""+userPreferences.automaticChecks);
            p.setProperty("preferences.downloads",""+userPreferences.automaticDownloads);
            p.setProperty("preferences.unmeteredOnly",""+userPreferences.unmeteredOnly);
        }
        p.setProperty("version","2"); p.setProperty("partition",partition==null ? "" : partition);
        p.setProperty("kind",kind.name()); p.setProperty("explicit",""+explicit); p.setProperty("lastKind",lastKind.name()); p.setProperty("lastExplicit",""+lastExplicit);
        p.setProperty("due",""+due); p.setProperty("nextCheck",""+nextCheck); p.setProperty("lastCheck",""+lastCheck); p.setProperty("attempts",""+attempts);
        p.setProperty("phase",phase.name()); if(error!=null) p.setProperty("error",error);
        p.setProperty("localHintPending",""+localHintPending); p.setProperty("lastLocalCheck",""+lastLocalCheck);
        p.setProperty("pushEvents",String.join(",",pushEvents)); p.setProperty("lastPush",""+lastPush); offer(p,"declined.",declined);
        offer(p,"target.",target); offer(p,"lastTarget.",lastTarget); offer(p,"available.",available);
        PendingRetry.writeRecord(file,p);
    }
    /** Restores durable intent and returns the previously effective schedule, if any. */
    private UpdateSchedule load() throws IOException {
        if(!Files.exists(file)) return null;
        if(Files.size(file)>65536) throw new IOException("Invalid update state");
        Properties p=new Properties(); try(InputStream in=Files.newInputStream(file)) { p.load(in); }
        try {
            String version=p.getProperty("version");
            if(!"1".equals(version) && !"2".equals(version)) throw new IllegalArgumentException();
            Map<String,String> values=new LinkedHashMap<>(), runtime=new LinkedHashMap<>();
            p.forEach((k,v)-> {
                String key=(String)k;
                if(key.startsWith("runtime.")) runtime.put(key.substring(8),(String)v); else values.put(key,(String)v);
            });
            UpdateSchedule persisted=UpdateScheduleCodec.read(values);
            // Version 1 stored only the effective schedule, which cannot distinguish policy
            // defaults from choices; it adopts the current policy. Intent below is kept.
            if("2".equals(version)) {
                runtimeSchedule=runtime.isEmpty() ? null : UpdateScheduleCodec.read(runtime);
                if(p.containsKey("preferences.checks")) userPreferences=new DeliveryPreferences(bool(p,"preferences.checks"),
                    bool(p,"preferences.downloads"),bool(p,"preferences.unmeteredOnly"));
            }
            kind=Kind.valueOf(p.getProperty("kind")); lastKind=Kind.valueOf(p.getProperty("lastKind"));
            explicit=Boolean.parseBoolean(p.getProperty("explicit")); lastExplicit=Boolean.parseBoolean(p.getProperty("lastExplicit"));
            due=Long.parseLong(p.getProperty("due")); nextCheck=Long.parseLong(p.getProperty("nextCheck")); lastCheck=Long.parseLong(p.getProperty("lastCheck"));
            attempts=Integer.parseInt(p.getProperty("attempts")); partition=p.getProperty("partition"); phase=DeliveryController.Activity.valueOf(p.getProperty("phase")); error=p.getProperty("error");
            if(due<0 || nextCheck<0 || lastCheck<0 || attempts<0 || attempts>11) throw new IllegalArgumentException();
            localHintPending=Boolean.parseBoolean(p.getProperty("localHintPending","false"));
            lastLocalCheck=Long.parseLong(p.getProperty("lastLocalCheck","0"));
            if(lastLocalCheck<0) throw new IllegalArgumentException();
            declined=offer(p,"declined."); lastPush=Long.parseLong(p.getProperty("lastPush","0"));
            String events=p.getProperty("pushEvents",""); if(!events.isEmpty()) pushEvents.addAll(Arrays.asList(events.split(",")));
            target=offer(p,"target."); lastTarget=offer(p,"lastTarget."); available=offer(p,"available.");
            return persisted;
        } catch(RuntimeException invalid) { throw new IOException("Invalid update state"); }
    }
    private static boolean bool(Properties p,String key) {
        String value=p.getProperty(key);
        if(!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException("Invalid preference");
        return Boolean.parseBoolean(value);
    }
    private static void offer(Properties p,String prefix,ExpectedArchive a) {
        if(a==null) return;
        p.setProperty(prefix+"id",a.releaseId); p.setProperty(prefix+"version",""+a.payloadVersion); p.setProperty(prefix+"size",""+a.archiveSize);
        p.setProperty(prefix+"manifest",a.manifestSha256); p.setProperty(prefix+"archive",a.archiveSha256);
    }
    private static ExpectedArchive offer(Properties p,String prefix) {
        if(!p.containsKey(prefix+"id")) return null;
        return new ExpectedArchive(p.getProperty(prefix+"id"),Long.parseLong(p.getProperty(prefix+"version")),p.getProperty(prefix+"manifest"),p.getProperty(prefix+"archive"),Long.parseLong(p.getProperty(prefix+"size")));
    }
}
