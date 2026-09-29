package com.lelloman.paravoidandroid.runtime;

import java.util.*;

/** Keep Android's UID boundary explicit; shared-UID distributors are unsupported. */
final class TriggerCallerVerifier {
    interface Identity {
        String[] packages(int uid);
        boolean signedBy(String name,String sha256);
    }
    static boolean trusted(int uid, Map<String,List<String>> callers, Identity identity) {
        if(uid<0) return false;
        String[] packages=identity.packages(uid);
        if(packages==null || packages.length!=1) return false;
        List<String> pins=callers.get(packages[0]);
        if(pins==null) return false;
        for(String pin:pins) if(identity.signedBy(packages[0],pin)) return true;
        return false;
    }
}
