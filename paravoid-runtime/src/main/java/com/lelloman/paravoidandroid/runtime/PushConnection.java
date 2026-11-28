package com.lelloman.paravoidandroid.runtime;

import com.lelloman.paravoidandroid.delivery.*;
import com.lelloman.paravoidandroid.updates.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.*;
import java.io.IOException;

/** One cancellable connection; failures use capped backoff and never escape to payload code. */
final class PushConnection {
    private final PushTransport transport;
    private final PushRequest request;
    private final PushAuthentication authentication;
    private final UpdateEngine engine;
    private final Signal signal=new Signal();
    PushConnection(PushTransport transport,PushRequest request,PushAuthentication authentication,UpdateEngine engine) {
        this.transport=transport; this.request=request; this.authentication=authentication; this.engine=engine;
    }
    void start() {
        Thread thread=new Thread(()-> {
            PushRetry retry=new PushRetry();
            // Built-in transport failures carry fixed texts, HTTP status codes or JDK socket errors; app adapters may not.
            boolean trustedMessages=transport instanceof WebSocketPushTransport;
            while(!signal.cancelled) {
                AtomicReference<Long> connectedAt=new AtomicReference<>();
                String phase="authentication";
                try {
                    Map<String,String> headers=authentication==null ? request.headers : authentication.headers(request);
                    PushRequest authenticated=new PushRequest(request.applicationId,request.shellContractId,request.channel,
                        request.endpoint,request.privateDirectory,headers);
                    phase="connection";
                    transport.run(authenticated,new PushTransport.Listener() {
                        public void received(byte[] event) { engine.pushEvent(event); }
                        public void connected() { connectedAt.compareAndSet(null,System.nanoTime()); }
                    },signal);
                    retry.finished(connectedMillis(connectedAt));
                } catch(Exception | LinkageError failure) {
                    retry.finished(connectedMillis(connectedAt));
                    // Retry without logging credentials or server data: type, phase and trusted fixed texts only.
                    String line=signal.cancelled ? null : retry.report(phase,failure,trustedMessages && "connection".equals(phase));
                    if(line!=null) android.util.Log.w("ParavoidAndroid",line);
                }
                long delay=retry.nextDelay();
                synchronized(signal) {
                    if(signal.cancelled) break;
                    try { signal.wait(delay); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            }
        },"paravoid-push"); thread.setDaemon(true); thread.start();
    }
    private static long connectedMillis(AtomicReference<Long> connectedAt) {
        Long start=connectedAt.get(); return start==null ? -1 : TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
    }
    void stop() { signal.cancel(); }
    private static final class Signal implements Cancellation {
        volatile boolean cancelled;
        private Runnable close;
        public void check() throws IOException { if(cancelled) throw new IOException("Cancelled"); }
        public synchronized AutoCloseable onCancel(Runnable action) { close=action; if(cancelled) action.run(); return ()-> { synchronized(this) { if(close==action) close=null; } }; }
        synchronized void cancel() { cancelled=true; if(close!=null) close.run(); notifyAll(); }
    }
}
