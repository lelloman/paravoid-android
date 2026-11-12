package com.lelloman.paravoidandroid.runtime;

import org.junit.Test;
import static org.junit.Assert.*;

public class StoreBusyReportsTest {
    @Test public void noStoresConfiguredMeansNoReports() {
        assertTrue(StoreBusyReports.parse(null).isEmpty());
        assertTrue(StoreBusyReports.parse("").isEmpty());
    }
    @Test public void eachStoreIsReachedAtItsUpdateConsentProvider() {
        assertEquals(java.util.Arrays.asList("com.a.update-consent","com.lelloman.store.update-consent"),
            StoreBusyReports.parse("com.a;com.lelloman.store"));
    }
}
