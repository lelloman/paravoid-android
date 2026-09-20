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
