package com.lelloman.paravoidandroid.contract;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** APK-pinned package -> accepted certificate SHA-256 fingerprints. */
public final class LocalTriggerCallers {
    private LocalTriggerCallers() {}
    public static Map<String,List<String>> read(String json) throws ContractException {
        if(json==null) return Collections.emptyMap();
        Object parsed=StrictJson.parse(json.getBytes(StandardCharsets.UTF_8),16384);
        if(!(parsed instanceof Map)) throw invalid();
        Map<String,List<String>> result=new TreeMap<>();
        for(Map.Entry<?,?> entry:((Map<?,?>)parsed).entrySet()) {
            if(!(entry.getKey() instanceof String) || !((String)entry.getKey()).matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) throw invalid();
            if(!(entry.getValue() instanceof List) || ((List<?>)entry.getValue()).isEmpty()) throw invalid();
            TreeSet<String> pins=new TreeSet<>();
            for(Object pin:(List<?>)entry.getValue()) {
                if(!(pin instanceof String) || !((String)pin).matches("[0-9a-f]{64}") || !pins.add((String)pin)) throw invalid();
            }
            result.put((String)entry.getKey(),Collections.unmodifiableList(new ArrayList<>(pins)));
        }
        return Collections.unmodifiableMap(result);
    }
    private static ContractException invalid() {
        return new ContractException(ContractException.Code.MALFORMED,"Invalid local trigger callers");
    }
}
