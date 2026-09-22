package com.lelloman.paravoidandroid.runtime;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import com.lelloman.paravoidandroid.delivery.*;
import com.lelloman.paravoidandroid.runtime.lifecycle.RuntimeLifecycle;
import java.io.*;
import java.util.Arrays;

/** Android identity/clock boundary. No network, cached ApplicationInfo or payload class loading. */
final class AndroidLifecycleEnvironment implements ApkInstalledStateSource.Location,
        RuntimeLifecycle.Clock, DeliveryClient.Clock {
    private final Context context;
    private final String boot;
    AndroidLifecycleEnvironment(Context context) throws ContractException {
        this.context = context;
        try {
            int count = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
            if (count < 0) throw new IllegalStateException();
            boot = "android-boot-" + count;
        } catch (Settings.SettingNotFoundException | RuntimeException error) {
            throw new ContractException(ContractException.Code.CLOCK_INVALID, "Boot identity unavailable");
        }
    }
    private ApplicationInfo currentInfo() throws IOException {
        try { return context.getPackageManager().getApplicationInfo(context.getPackageName(), 0); }
        catch (PackageManager.NameNotFoundException error) { throw new IOException("Installed package unavailable"); }
    }
    public File currentBaseApk() throws IOException { return new File(currentInfo().sourceDir); }
    public boolean debuggable() throws IOException { return (currentInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0; }
    public long unixSeconds() { return System.currentTimeMillis() / 1000; }
    public long elapsedSeconds() { return SystemClock.elapsedRealtime() / 1000; }
    public long elapsedMillis() { return SystemClock.elapsedRealtime(); }
    public String bootId() { return boot; }
    boolean mainProcess() throws IOException { return Application.getProcessName().equals(currentInfo().processName); }
    RequestScope scope(ShellPolicy policy) {
        String[] abis = android.os.Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        return new RequestScope(policy.applicationId, policy.shellContractId, policy.channel, Build.VERSION.SDK_INT,
            Arrays.asList(abis), Protocol.RUNTIME_ABI);
    }
}
