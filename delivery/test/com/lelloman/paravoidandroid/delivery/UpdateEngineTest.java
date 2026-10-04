package com.lelloman.paravoidandroid.delivery;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import com.lelloman.paravoidandroid.updates.UpdateSchedule;
import static com.lelloman.paravoidandroid.delivery.TransportTest.*;

public final class UpdateEngineTest {
    static byte[] event(String id) {
        return ("{\"version\":1,\"type\":\"updates_changed\",\"applicationId\":\"example.app\",\"shellContractId\":\""+"a".repeat(64)+"\",\"channel\":\"stable\",\"eventId\":\""+id+"\"}").getBytes(StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        try(DeliveryClientTest.Setup s=new DeliveryClientTest.Setup(false)) {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.atomic.AtomicReference<UpdateEngine.Work> work=new java.util.concurrent.atomic.AtomicReference<>();
                UpdateEngine e=new UpdateEngine(s.client,s.life,s.scope,s.clock,s.f.dir.resolve("engine").toFile(),worker,Runnable::run,
                    UpdateSchedule.defaults(),null,false,work::set);
                e.configurePush(true,true); worker.submit(()->{}).get();
                e.pushEvent(event("first")); worker.submit(()->{}).get();
                check(work.get().kind==UpdateEngine.Kind.CHECK && !work.get().explicit);
                s.head(); e.runJob(false,1,()->{}); worker.submit(()->{}).get();
                check(e.current().promptRequired && s.f.requests==1 && s.life.stages==0);
                check(work.get().kind==UpdateEngine.Kind.NONE);
                e.pushEvent(event("first")); worker.submit(()->{}).get(); check(work.get().kind==UpdateEngine.Kind.NONE);
                e.dismiss(e.current().available); worker.submit(()->{}).get(); check(!e.current().promptRequired);
                s.cached(); s.f.responses.add(new Fake(200,ARCHIVE)); e.updateNow(e.current().available); worker.submit(()->{}).get();
                check(s.life.stages==1 && e.current().activity==DeliveryController.Activity.READY);
                e.configurePush(false,false); worker.submit(()->{}).get();
                e.pushEvent(event("disabled")); worker.submit(()->{}).get(); check(work.get().kind==UpdateEngine.Kind.NONE);
                s.cached(); e.checkNow(); worker.submit(()->{}).get(); check(s.life.stages==1);
            } finally { worker.shutdownNow(); check(worker.awaitTermination(5,TimeUnit.SECONDS)); }
        }
        localHints();
        earlyPeriodicWakeupKeepsDeadline();
        unavailableRetriesAndSurvivesReload();
        unavailableRetryBudgetIsBounded();
        unavailableFromOldContractStartsFresh();
        terminalStageFailuresIgnoreHints();
        localHintsPreserveRetries();
        policyDefaultsReachExistingInstalls();
        System.out.println("PASS update engine: push scope, duplicate suppression, consent, explicit update, check-only, disabled push, policy defaults vs durable choices");
    }
    static void localHints() throws Exception {
        try(DeliveryClientTest.Setup s=new DeliveryClientTest.Setup(false)) {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.atomic.AtomicReference<UpdateEngine.Work> work=new java.util.concurrent.atomic.AtomicReference<>();
                java.io.File state=s.f.dir.resolve("local-engine").toFile();
                UpdateEngine e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                    UpdateSchedule.defaults().preferences(true,false,false),null,false,work::set);
                e.configurePush(false,false);
                e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                check(work.get().kind==UpdateEngine.Kind.CHECK && !work.get().explicit);
                long firstDue=work.get().dueSeconds;
                e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                check(work.get().dueSeconds==firstDue);
                s.head(); e.runJob(false,1,()->{}); worker.submit(()->{}).get();
                check(s.f.requests==1 && s.life.stages==0 && e.current().available!=null);
                e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                long due=work.get().dueSeconds; check(due==s.clock.wall+60);
                // Reload preserves a throttled hint; more hints cannot move its deadline.
                e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                    UpdateSchedule.defaults(),null,false,work::set);
                e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                check(work.get().dueSeconds==due);
                e.runJob(false,2,()->{}); worker.submit(()->{}).get(); check(s.f.requests==1);
                e.schedule(UpdateSchedule.defaults().preferences(false,true,false)); worker.submit(()->{}).get();
                e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                check(work.get().kind==UpdateEngine.Kind.NONE);
                e.schedule(UpdateSchedule.defaults().preferences(true,true,false)); worker.submit(()->{}).get();
                s.clock.wall=due; e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                s.cached(); e.runJob(false,3,()->{}); worker.submit(()->{}).get();
                check(work.get().kind==UpdateEngine.Kind.UPDATE && !work.get().explicit);
                // A hint during pending download survives without replacing update intent.
                e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                check(work.get().kind==UpdateEngine.Kind.UPDATE);
                s.cached(); s.f.responses.add(new Fake(200,ARCHIVE));
                e.runJob(true,4,()->{}); worker.submit(()->{}).get();
                check(s.life.stages==1 && work.get().kind==UpdateEngine.Kind.CHECK);
                check(work.get().dueSeconds>=due+60);
            } finally { worker.shutdownNow(); worker.awaitTermination(5,TimeUnit.SECONDS); }
        }
    }

    static void earlyPeriodicWakeupKeepsDeadline() throws Exception {
        try(DeliveryClientTest.Setup s=new DeliveryClientTest.Setup(false)) {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.atomic.AtomicReference<UpdateEngine.Work> work=new java.util.concurrent.atomic.AtomicReference<>();
                java.io.File state=s.f.dir.resolve("early-periodic-engine").toFile();
                UpdateSchedule schedule=UpdateSchedule.defaults().preferences(true,false,false);
                UpdateEngine e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                    schedule,null,false,work::set);
                s.head(); e.checkNow(); worker.submit(()->{}).get();
                long deadline=s.clock.wall+schedule.intervalSeconds;
                check(work.get().checkDeadlineSeconds()==deadline);
                s.clock.wall=deadline-schedule.flexSeconds;
                e.runJob(false,1,()->{}); worker.submit(()->{}).get();
                check(s.f.requests==1 && work.get().checkDeadlineSeconds()==deadline);
                // Process death must restore the same one-off deadline, not wait for another period.
                e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                    schedule,null,false,work::set);
                check(work.get().checkDeadlineSeconds()==deadline);
                s.clock.wall=deadline;
                s.cached(); e.runJob(false,2,()->{}); worker.submit(()->{}).get();
                check(s.f.requests==2 && work.get().checkDeadlineSeconds()==deadline+schedule.intervalSeconds);
                e.preferences(new DeliveryPreferences(false,false,false)); worker.submit(()->{}).get();
                check(work.get().checkDeadlineSeconds()==null);
                s.cached(); e.checkNow(); worker.submit(()->{}).get();
                check(work.get().checkDeadlineSeconds()==null);
            } finally { worker.shutdownNow(); worker.awaitTermination(5,TimeUnit.SECONDS); }
        }
    }

    static void unavailableRetriesAndSurvivesReload() throws Exception {
        try(DeliveryClientTest.Setup s=new DeliveryClientTest.Setup(false)) {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.atomic.AtomicReference<UpdateEngine.Work> work=new java.util.concurrent.atomic.AtomicReference<>();
                java.io.File state=s.f.dir.resolve("busy-engine").toFile();
                UpdateEngine e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                    UpdateSchedule.defaults(),null,false,work::set);
                try(DeliveryLocks.Claim held=DeliveryLocks.tryAcquire(s.f.dir.resolve("transfer.lock"))) {
                    check(held!=null);
                    e.runJob(false,1,()->{}); worker.submit(()->{}).get();
                    check(e.current().activity==DeliveryController.Activity.WAITING_TO_RETRY && s.f.requests==0);
                    long due=work.get().dueSeconds;
                    check(due==s.clock.wall+30 && work.get().kind==UpdateEngine.Kind.CHECK);
                    e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                    check(work.get().dueSeconds==due);
                    // Reproduce the old shell's terminal error record and upgrade without manual retry.
                    java.nio.file.Path record=state.toPath().resolve("operations.properties");
                    java.util.Properties p=new java.util.Properties();
                    try(java.io.InputStream in=java.nio.file.Files.newInputStream(record)) { p.load(in); }
                    p.setProperty("kind","NONE"); p.setProperty("phase","ERROR");
                    p.setProperty("preferences.checks","false"); p.setProperty("preferences.downloads","false");
                    p.setProperty("preferences.unmeteredOnly","true");
                    PendingRetry.writeRecord(record,p);
                    e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                        UpdateSchedule.defaults(),null,false,work::set);
                    check(e.current().activity==DeliveryController.Activity.ERROR && work.get().kind==UpdateEngine.Kind.NONE);
                    p.remove("preferences.checks"); p.remove("preferences.downloads"); p.remove("preferences.unmeteredOnly");
                    PendingRetry.writeRecord(record,p);
                    e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                        UpdateSchedule.defaults(),null,false,work::set);
                    check(e.current().activity==DeliveryController.Activity.WAITING_TO_RETRY && work.get().dueSeconds==due);
                }
                s.clock.wall=work.get().dueSeconds;
                s.head(); e.runJob(false,2,()->{}); worker.submit(()->{}).get();
                check(work.get().kind==UpdateEngine.Kind.UPDATE && !work.get().explicit);
                s.life.stageFailure=com.lelloman.paravoidandroid.contract.ContractException.Code.UNAVAILABLE;
                s.cached(); s.f.responses.add(new Fake(200,ARCHIVE));
                e.runJob(true,3,()->{}); worker.submit(()->{}).get();
                check(e.current().activity==DeliveryController.Activity.WAITING_TO_RETRY && work.get().kind==UpdateEngine.Kind.UPDATE);
                long due=work.get().dueSeconds;
                e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                    UpdateSchedule.defaults(),null,false,work::set);
                check(work.get().dueSeconds==due && work.get().kind==UpdateEngine.Kind.UPDATE);
                s.life.stageFailure=null; s.clock.wall=due;
                s.cached(); s.f.responses.add(new Fake(200,ARCHIVE));
                e.runJob(true,4,()->{}); worker.submit(()->{}).get();
                check(e.current().activity==DeliveryController.Activity.READY && s.life.stages==1);
            } finally { worker.shutdownNow(); worker.awaitTermination(5,TimeUnit.SECONDS); }
        }
    }

    static void unavailableRetryBudgetIsBounded() throws Exception {
        try(DeliveryClientTest.Setup s=new DeliveryClientTest.Setup(false)) {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.atomic.AtomicReference<UpdateEngine.Work> work=new java.util.concurrent.atomic.AtomicReference<>();
                java.io.File state=s.f.dir.resolve("busy-exhausted-engine").toFile();
                UpdateSchedule schedule=UpdateSchedule.defaults();
                UpdateEngine e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                    schedule,null,false,work::set);
                try(DeliveryLocks.Claim held=DeliveryLocks.tryAcquire(s.f.dir.resolve("transfer.lock"))) {
                    check(held!=null);
                    for(int i=0;i<=schedule.maxRetries;i++) {
                        if(i>0) s.clock.wall=work.get().dueSeconds;
                        e.runJob(false,10+i,()->{}); worker.submit(()->{}).get();
                        check(i==schedule.maxRetries ? e.current().activity==DeliveryController.Activity.ERROR
                            : e.current().activity==DeliveryController.Activity.WAITING_TO_RETRY);
                    }
                    check(s.f.requests==0 && work.get().kind==UpdateEngine.Kind.NONE);
                    e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                        schedule,null,false,work::set);
                    check(e.current().activity==DeliveryController.Activity.ERROR && work.get().kind==UpdateEngine.Kind.NONE);
                }
            } finally { worker.shutdownNow(); worker.awaitTermination(5,TimeUnit.SECONDS); }
        }
    }

    static void unavailableFromOldContractStartsFresh() throws Exception {
        try(DeliveryClientTest.Setup s=new DeliveryClientTest.Setup(false)) {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.atomic.AtomicReference<UpdateEngine.Work> work=new java.util.concurrent.atomic.AtomicReference<>();
                java.io.File state=s.f.dir.resolve("old-contract-engine").toFile();
                UpdateEngine e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                    UpdateSchedule.defaults(),null,false,work::set);
                java.nio.file.Path record=state.toPath().resolve("operations.properties");
                java.util.Properties p=new java.util.Properties();
                try(java.io.InputStream in=java.nio.file.Files.newInputStream(record)) { p.load(in); }
                p.setProperty("partition","old-shell-contract"); p.setProperty("phase","ERROR");
                p.setProperty("error","UNAVAILABLE"); p.setProperty("lastKind","UPDATE");
                p.setProperty("explicit","true"); p.setProperty("lastExplicit","true"); p.setProperty("attempts","4");
                PendingRetry.writeRecord(record,p);
                e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                    UpdateSchedule.defaults(),null,false,work::set);
                check(e.current().errorCode==null && e.current().activity==DeliveryController.Activity.IDLE);
                check(work.get().kind==UpdateEngine.Kind.NONE && !work.get().explicit && work.get().checkDeadlineSeconds()==0);
                e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                check(work.get().kind==UpdateEngine.Kind.CHECK && !work.get().explicit);
                s.head(); e.runJob(false,1,()->{}); worker.submit(()->{}).get();
                check(s.f.requests==1 && work.get().kind==UpdateEngine.Kind.UPDATE);
            } finally { worker.shutdownNow(); worker.awaitTermination(5,TimeUnit.SECONDS); }
        }
    }

    static void terminalStageFailuresIgnoreHints() throws Exception {
        for(com.lelloman.paravoidandroid.contract.ContractException.Code code : new com.lelloman.paravoidandroid.contract.ContractException.Code[]{
                com.lelloman.paravoidandroid.contract.ContractException.Code.INVALID_SIGNATURE,
                com.lelloman.paravoidandroid.contract.ContractException.Code.CREDENTIAL_UNAVAILABLE,
                com.lelloman.paravoidandroid.contract.ContractException.Code.INSUFFICIENT_STORAGE}) {
            try(DeliveryClientTest.Setup s=new DeliveryClientTest.Setup(false)) {
                ExecutorService worker=Executors.newSingleThreadExecutor();
                try {
                    java.util.concurrent.atomic.AtomicReference<UpdateEngine.Work> work=new java.util.concurrent.atomic.AtomicReference<>();
                    java.io.File state=s.f.dir.resolve("terminal-engine").toFile();
                    UpdateEngine e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                        UpdateSchedule.defaults(),null,false,work::set);
                    s.life.stageFailure=code;
                    s.head(); s.f.responses.add(new Fake(200,ARCHIVE));
                    e.updateNow(); worker.submit(()->{}).get();
                    check(e.current().activity==DeliveryController.Activity.ERROR && e.current().errorCode.equals(code.name()));
                    e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                    check(work.get().kind==UpdateEngine.Kind.NONE);
                    e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                        UpdateSchedule.defaults(),null,false,work::set);
                    check(e.current().activity==DeliveryController.Activity.ERROR && work.get().kind==UpdateEngine.Kind.NONE);
                } finally { worker.shutdownNow(); worker.awaitTermination(5,TimeUnit.SECONDS); }
            }
        }
    }

    static void localHintsPreserveRetries() throws Exception {
        try(DeliveryClientTest.Setup s=new DeliveryClientTest.Setup(false)) {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.atomic.AtomicReference<UpdateEngine.Work> work=new java.util.concurrent.atomic.AtomicReference<>();
                java.io.File state=s.f.dir.resolve("local-retry").toFile();
                UpdateEngine e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                    UpdateSchedule.defaults().preferences(true,false,false),null,false,work::set);
                e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                s.f.responses.add(new Fake(503,new byte[0]));
                e.runJob(false,1,()->{}); worker.submit(()->{}).get();
                long due=work.get().dueSeconds;
                check(e.current().activity==DeliveryController.Activity.WAITING_TO_RETRY);
                check(work.get().checkDeadlineSeconds()==due);
                e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                check(work.get().dueSeconds==due && work.get().kind==UpdateEngine.Kind.CHECK);
                check(java.nio.file.Files.readString(state.toPath().resolve("operations.properties")).contains("attempts=1"));
                java.nio.file.Files.write(state.toPath().resolve("provider-running"),new byte[]{1});
                e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,
                    UpdateSchedule.defaults(),null,true,work::set);
                e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                check("PROVIDER_INTERRUPTED".equals(e.current().errorCode) && work.get().kind==UpdateEngine.Kind.NONE);
            } finally { worker.shutdownNow(); worker.awaitTermination(5,TimeUnit.SECONDS); }
        }
    }


    /** Only explicit choices are durable; an updated shell's schedule reaches existing installs. */
    static void policyDefaultsReachExistingInstalls() throws Exception {
        try(DeliveryClientTest.Setup s=new DeliveryClientTest.Setup(false)) {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.atomic.AtomicReference<UpdateEngine.Work> work=new java.util.concurrent.atomic.AtomicReference<>();
                java.io.File state=s.f.dir.resolve("policy-engine").toFile();
                java.nio.file.Path file=state.toPath().resolve("operations.properties");
                UpdateSchedule manual=UpdateSchedule.defaults().preferences(false,false,true);
                UpdateSchedule hinted=new UpdateSchedule(172800,21600,true,true,false,true,false,false,false,30,3600,3,java.util.Collections.emptyMap());
                UpdateEngine e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,manual,null,false,work::set);
                e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                check(work.get().kind==UpdateEngine.Kind.NONE);

                // The next shell enables checks with a longer interval; nothing was chosen at runtime.
                e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,hinted,null,false,work::set);
                check(e.current().schedule.checks && e.current().schedule.downloads && e.current().schedule.intervalSeconds==172800);
                e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                check(work.get().kind==UpdateEngine.Kind.CHECK && !work.get().explicit);
                s.head(); e.runJob(false,1,()->{}); worker.submit(()->{}).get();
                check(s.f.requests==1 && work.get().nextCheckSeconds==s.clock.wall+172800);

                // A user choice survives restarts and policy changes; other fields follow the policy.
                e.preferences(new DeliveryPreferences(false,true,true)); worker.submit(()->{}).get();
                UpdateSchedule shorter=new UpdateSchedule(86400,7200,true,true,false,true,false,false,false,30,3600,3,java.util.Collections.emptyMap());
                e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,shorter,null,false,work::set);
                check(!e.current().schedule.checks && e.current().schedule.downloads && e.current().schedule.intervalSeconds==86400);
                check(work.get().nextCheckSeconds==s.clock.wall+86400);
                e.localHint(saved->check(saved)); worker.submit(()->{}).get();
                check(work.get().kind==UpdateEngine.Kind.NONE);

                // A complete runtime schedule replaces earlier preferences and is durable too.
                UpdateSchedule runtime=new UpdateSchedule(43200,3600,true,false,false,true,false,false,false,30,3600,3,java.util.Collections.emptyMap());
                e.schedule(runtime); worker.submit(()->{}).get();
                e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,hinted,null,false,work::set);
                check(e.current().schedule.checks && !e.current().schedule.downloads && e.current().schedule.intervalSeconds==43200);
                e.preferences(new DeliveryPreferences(true,true,true)); worker.submit(()->{}).get();
                e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,hinted,null,false,work::set);
                check(e.current().schedule.downloads && e.current().schedule.intervalSeconds==43200);

                // Version 1 stored only the effective schedule; it adopts the current policy and keeps intent.
                String legacy=java.nio.file.Files.readString(file).replace("version=2","version=1").replace("checks=true","checks=false");
                java.nio.file.Files.writeString(file,legacy);
                e=new UpdateEngine(s.client,s.life,s.scope,s.clock,state,worker,Runnable::run,hinted,null,false,work::set);
                check(e.current().schedule.checks && e.current().schedule.intervalSeconds==172800);
                check(e.current().lastCheckSeconds==s.clock.wall && work.get().nextCheckSeconds==s.clock.wall+172800);
                check(java.nio.file.Files.readString(file).contains("version=2") && !java.nio.file.Files.readString(file).contains("runtime."));
            } finally { worker.shutdownNow(); worker.awaitTermination(5,TimeUnit.SECONDS); }
        }
    }
}
