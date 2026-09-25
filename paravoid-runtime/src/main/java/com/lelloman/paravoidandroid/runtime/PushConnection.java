package com.lelloman.paravoidandroid.runtime;

import com.lelloman.paravoidandroid.delivery.*;
import com.lelloman.paravoidandroid.updates.*;
import java.util.concurrent.*;
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
            long delay=1000;
            while(!signal.cancelled) {
                try {
                    Map<String,String> headers=authentication==null ? request.headers : authentication.headers(request);
                    PushRequest authenticated=new PushRequest(request.applicationId,request.shellContractId,request.channel,
                        request.endpoint,request.privateDirectory,headers);
                    transport.run(authenticated,new PushTransport.Listener() {
                        public void received(byte[] event) { engine.pushEvent(event); }
                        public void connected() { }
                    },signal);
                } catch(Exception | LinkageError failure) { /* Retry without logging credentials or server data. */ }
                synchronized(signal) {
                    if(signal.cancelled) break;
                    try { signal.wait(delay); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
                delay=Math.min(60000,delay*2);
            }
        },"paravoid-push"); thread.setDaemon(true); thread.start();
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
