package com.lelloman.paravoidandroid.runtime;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class RequestScopeAbisTest {
    @Test public void dropsLegacyArmeabiOfThirtyTwoBitArmDevices() {
        // A Mi Box S (Android 11, 32-bit userspace) reports armeabi-v7a, armeabi.
        assertEquals(Arrays.asList("armeabi-v7a"),
            AndroidLifecycleEnvironment.supportedAbis(new String[]{"armeabi-v7a", "armeabi"}));
    }

    @Test public void keepsKnownAbisInDeviceOrderWithoutDuplicates() {
        assertEquals(Arrays.asList("x86_64", "arm64-v8a"),
            AndroidLifecycleEnvironment.supportedAbis(new String[]{"x86_64", "mips", "arm64-v8a", "x86_64"}));
    }
}
