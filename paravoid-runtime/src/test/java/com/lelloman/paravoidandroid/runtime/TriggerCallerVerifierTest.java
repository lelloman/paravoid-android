package com.lelloman.paravoidandroid.runtime;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TriggerCallerVerifierTest {
    private static final Map<String,List<String>> TRUST=Map.of("com.store",List.of("old","current"),"com.other",List.of("other"));
    private boolean verify(String[] names,String certificate) {
        return TriggerCallerVerifier.trusted(123,TRUST,new TriggerCallerVerifier.Identity() {
            public String[] packages(int uid) { assertEquals(123,uid); return names; }
            public boolean signedBy(String name,String pin) { return pin.equals(certificate); }
        });
    }
    @Test public void authenticatesPackageAndCertificateTogether() {
        assertTrue(verify(new String[]{"com.store"},"current"));
        assertTrue(verify(new String[]{"com.store"},"old"));
        assertTrue(verify(new String[]{"com.other"},"other"));
        assertFalse(verify(new String[]{"com.store"},"imposter"));
        assertFalse(verify(new String[]{"com.spoof"},"current"));
        assertFalse(verify(null,"current"));
        assertFalse(verify(new String[]{"com.store","com.sibling"},"current"));
        assertFalse(verify(new String[]{},"current"));
    }
}
