package com.lelloman.paravoidandroid.delivery;
import com.lelloman.paravoidandroid.updates.UpdateSchedule;
import java.util.*;
/** String-only representation shared by installed defaults, durable state and IPC. */
public final class UpdateScheduleCodec {
    private UpdateScheduleCodec() {}
    public static UpdateSchedule read(Map<String,String> p) {
        Map<String,String> data=new LinkedHashMap<>();
        p.forEach((k,v)-> { if(k.startsWith("data.")) data.put(k.substring(5),v); });
        return new UpdateSchedule(n(p,"intervalSeconds",21600),n(p,"flexSeconds",3600),
            b(p,"checks",true),b(p,"downloads",true),b(p,"checkUnmetered",false),b(p,"downloadUnmetered",true),
            b(p,"charging",false),b(p,"batteryNotLow",false),b(p,"deviceIdle",false),
            n(p,"retrySeconds",30),n(p,"maxRetrySeconds",3600),(int)n(p,"maxRetries",3),data);
    }
    public static Map<String,String> write(UpdateSchedule s) {
        Map<String,String> p=new LinkedHashMap<>();
        p.put("intervalSeconds",""+s.intervalSeconds); p.put("flexSeconds",""+s.flexSeconds);
        p.put("checks",""+s.checks); p.put("downloads",""+s.downloads); p.put("checkUnmetered",""+s.checkUnmetered);
        p.put("downloadUnmetered",""+s.downloadUnmetered); p.put("charging",""+s.charging);
        p.put("batteryNotLow",""+s.batteryNotLow); p.put("deviceIdle",""+s.deviceIdle);
        p.put("retrySeconds",""+s.retrySeconds); p.put("maxRetrySeconds",""+s.maxRetrySeconds); p.put("maxRetries",""+s.maxRetries);
        s.policyData.forEach((k,v)->p.put("data."+k,v)); return p;
    }
    private static long n(Map<String,String> p,String key,long fallback) { return Long.parseLong(p.getOrDefault(key,""+fallback)); }
    private static boolean b(Map<String,String> p,String key,boolean fallback) {
        String value=p.getOrDefault(key,""+fallback);
        if(!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid schedule boolean");
        return Boolean.parseBoolean(value);
    }
}
