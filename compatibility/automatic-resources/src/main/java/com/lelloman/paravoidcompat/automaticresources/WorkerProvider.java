package com.lelloman.paravoidcompat.automaticresources;

import android.os.Bundle;

public final class WorkerProvider extends EarlyProvider {
    @Override public Bundle call(String method, String arg, Bundle extras) {
        // Provider publication can precede Application.onCreate; wait on this Binder
        // thread, never in provider creation or the worker's main startup thread.
        try {
            if (!ProbeApplication.started.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Worker startup timed out");
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        Bundle result = new Bundle();
        result.putString("title", ProbeActivity.title(getContext()));
        result.putString("early", earlyTitle);
        result.putString("application", ProbeApplication.applicationTitle);
        result.putString("constructor", ProbeApplication.constructorTitle);
        result.putBoolean("before", beforeApplication);
        result.putInt("pid", android.os.Process.myPid());
        return result;
    }
}
