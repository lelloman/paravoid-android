package com.lelloman.paravoidandroid.contract;

import java.net.URI;
import java.util.*;

/** APK-pinned updater wiring; mutable user scheduling preferences are stored separately. */
public final class UpdateConfiguration {
    private UpdateConfiguration() {}
    public static void validate(Map<String,String> values) throws ContractException {
        Set<String> keys = new HashSet<>(Arrays.asList("mode", "metadataUrl", "payloadUrlTemplate",
            "checkerClass", "updaterClass", "policyClass", "intervalSeconds", "flexSeconds", "jobIdBase",
            "checks", "downloads", "checkUnmetered", "downloadUnmetered", "charging", "batteryNotLow",
            "deviceIdle", "retrySeconds", "maxRetrySeconds", "maxRetries", "pushEnabled", "pushWebSocketUrl", "pushTransportClass", "pushAuthenticationClass", "updateBehavior", "pushComponentClasses", "restartBehavior"));
        try {
            if (!keys.containsAll(values.keySet())) throw new IllegalArgumentException();
            String mode=values.getOrDefault("mode","api");
            if (!mode.equals("api") && !mode.equals("feed")) throw new IllegalArgumentException();
            for (String key : Arrays.asList("checkerClass","updaterClass","policyClass","pushTransportClass","pushAuthenticationClass")) {
                String name=values.getOrDefault(key,"");
                if (!name.isEmpty() && !name.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) throw new IllegalArgumentException();
            }
            String restart=values.getOrDefault("restartBehavior","manual");
            if(!Arrays.asList("manual","prompt","automatic").contains(restart)) throw new IllegalArgumentException();
            String behavior=values.getOrDefault("updateBehavior","automatic");
            if(!behavior.equals("automatic") && !behavior.equals("prompt")) throw new IllegalArgumentException();
            String ws=values.getOrDefault("pushWebSocketUrl","");
            if(!ws.isEmpty()) {
                URI uri=URI.create(ws);
                if(!Arrays.asList("wss","ws").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null
                    || uri.getQuery()!=null || !uri.normalize().equals(uri)) throw new IllegalArgumentException();
            }
            for(String name:values.getOrDefault("pushComponentClasses","").split(";"))
                if(!name.isEmpty() && !name.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) throw new IllegalArgumentException();
            if("true".equals(values.get("pushEnabled")) && ws.isEmpty() && values.getOrDefault("pushTransportClass","").isEmpty()
                && values.getOrDefault("pushComponentClasses","").isEmpty()) throw new IllegalArgumentException();
            if (mode.equals("feed")) {
                endpoint(values.get("metadataUrl"));
                endpoint(values.getOrDefault("payloadUrlTemplate", "").replace("{releaseId}", "r1"));
            } else if (!values.getOrDefault("metadataUrl", "").isEmpty() || !values.getOrDefault("payloadUrlTemplate", "").isEmpty()) {
                throw new IllegalArgumentException();
            }
            long interval=integer(values,"intervalSeconds",21600,900,365L*86400);
            integer(values,"flexSeconds",3600,300,interval);
            long retry=integer(values,"retrySeconds",30,30,3600);
            integer(values,"maxRetrySeconds",3600,retry,3600);
            integer(values,"maxRetries",3,0,10);
            integer(values,"jobIdBase",0x50560000,1,Integer.MAX_VALUE-4);
            for(String key: Arrays.asList("checks","downloads","checkUnmetered","downloadUnmetered","charging","batteryNotLow","deviceIdle","pushEnabled"))
                if(values.containsKey(key) && !values.get(key).equals("true") && !values.get(key).equals("false")) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new ContractException(ContractException.Code.MALFORMED,"Invalid installed update configuration");
        }
    }
    private static long integer(Map<String,String> map,String key,long fallback,long min,long max) {
        long n=Long.parseLong(map.getOrDefault(key,Long.toString(fallback)));
        if(n<min || n>max) throw new IllegalArgumentException(); return n;
    }
    private static void endpoint(String value) {
        URI uri=URI.create(value);
        if(!Arrays.asList("https","http").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                || uri.getFragment()!=null || !uri.normalize().equals(uri) || uri.getPath().isEmpty()) throw new IllegalArgumentException();
    }
}
