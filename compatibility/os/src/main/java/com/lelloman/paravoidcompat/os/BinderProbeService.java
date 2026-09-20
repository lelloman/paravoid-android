package com.lelloman.paravoidcompat.os;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import com.lelloman.paravoidcompat.os.contract.*;
import java.util.List;
import java.util.UUID;

public final class BinderProbeService extends Service {
    private final String instance = UUID.randomUUID().toString();
    private final IProbe.Stub endpoint = new IProbe.Stub() {
        @Override public WireMessage exchange(WireMessage request, IProbeCallback callback) throws RemoteException {
            if (request == null) return null;
            ClassLoader loader = BinderProbeService.class.getClassLoader();
            boolean isolated = true;
            if (getPackageName().endsWith(".paravoid")) {
                try { loader.getParent().loadClass(IProbe.class.getName()); isolated = false; }
                catch (ClassNotFoundException expected) { }
            }
            boolean correct = request.getClass().getClassLoader() == loader
                && IProbe.Stub.class.getClassLoader() == loader && callback.getClass().getClassLoader() == loader
                && Looper.myLooper() != Looper.getMainLooper() && isolated;
            WireMessage response = new WireMessage("echo-λ-" + request.text, android.os.Process.myPid(),
                Binder.getCallingUid(), instance, correct);
            callback.received(response);
            return response;
        }
        @Override public List<WireMessage> echoList(List<WireMessage> messages) { return messages; }
        @Override public void reject() { throw new IllegalArgumentException("probe-rejected"); }
        @Override public android.os.Bundle exchangeBundle(android.os.Bundle request, boolean prepareLoader) {
            android.os.Bundle response = new android.os.Bundle();
            try {
                // AIDL Bundle unmarshalling requires an explicit loader in normal apps too.
                if (prepareLoader) request.setClassLoader(getClassLoader());
                android.os.Bundle nested = request.getBundle("nested");
                if (prepareLoader) nested.setClassLoader(getClassLoader());
                WireMessage value = nested.getParcelable("value");
                android.os.Bundle reply = new android.os.Bundle();
                reply.putParcelable("value", new WireMessage("bundle-λ-" + value.text,
                    android.os.Process.myPid(), Binder.getCallingUid(), instance,
                    value.getClass().getClassLoader() == BinderProbeService.class.getClassLoader()));
                response.putBundle("nested", reply);
                response.putString("status", "PASS");
            } catch (Exception error) { response.putString("status", error.toString()); }
            return response;
        }
    };
    @Override public IBinder onBind(Intent intent) {
        String observed;
        try {
            WireMessage value = intent.getParcelableExtra("bindingParcel");
            observed = value == null ? "MISSING" : value.text;
        } catch (Exception error) { observed = error.toString(); }
        getSharedPreferences("os-probe", MODE_PRIVATE).edit().putString("binderBinding", observed)
            .putString("binderBindingInstance", instance).commit();
        return endpoint;
    }
    @Override public void onDestroy() {
        getSharedPreferences("os-probe", MODE_PRIVATE).edit().putString("binderDestroyed", instance).commit();
        super.onDestroy();
    }
}
