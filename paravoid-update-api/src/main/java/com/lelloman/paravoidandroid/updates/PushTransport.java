package com.lelloman.paravoidandroid.updates;

/** Optional shell-packaged notification adapter. No notification vendor is assumed.
 * run executes on a dedicated thread, may block until cancelled, and must cooperate with cancellation.
 * Registration must be idempotent and persist its own opaque state in privateDirectory.
 * OS-delivered messages can enter through ParavoidPush.receive even when run is not alive. */
public interface PushTransport {
    void run(PushRequest request, Listener listener, Cancellation cancellation) throws Exception;
    interface Listener {
        /** Bounded UTF-8 Paravoid update-event JSON; never a URL, executable payload, or install command. */
        void received(byte[] event);
        /** A new/reconnected subscription may have missed events: request a coalesced check. */
        void connected();
    }
}
