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
        System.out.println("PASS update engine: push scope, duplicate suppression, consent, explicit update, check-only, disabled push");
    }
}
