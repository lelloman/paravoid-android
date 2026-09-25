package com.lelloman.paravoidandroid.runtime;

import com.lelloman.paravoidandroid.contract.Protocol.ExpectedArchive;
import org.junit.Test;
import static org.junit.Assert.*;

public class RestartPolicyTest {
    @Test public void userToggleOverridesAutomaticRestartWithoutChangingPromptPolicy() {
        assertEquals("automatic",RestartPolicy.effectiveBehavior("manual",true));
        assertEquals("automatic",RestartPolicy.effectiveBehavior("prompt",true));
        assertEquals("manual",RestartPolicy.effectiveBehavior("automatic",false));
        assertEquals("prompt",RestartPolicy.effectiveBehavior("prompt",false));
    }
    private ExpectedArchive offer(String hash) { return new ExpectedArchive("release",42,"b".repeat(64),hash.repeat(64),100); }
    @Test public void policyRequiresStagedUpdateAndExplicitOptIn() {
        ExpectedArchive pending=offer("a");
        assertFalse(RestartPolicy.shouldOffer("manual",pending,null,""));
        assertFalse(RestartPolicy.shouldOffer("unknown",pending,null,""));
        assertFalse(RestartPolicy.shouldOffer("automatic",null,null,""));
        assertTrue(RestartPolicy.shouldOffer("automatic",pending,null,""));
        assertTrue(RestartPolicy.shouldOffer("prompt",pending,null,""));
    }
    @Test public void declinedOrAttemptedArchiveDoesNotLoopButNewOfferCanRestart() {
        ExpectedArchive pending=offer("a");
        assertFalse(RestartPolicy.shouldOffer("automatic",pending,pending,""));
        assertFalse(RestartPolicy.shouldOffer("automatic",pending,null,pending.archiveSha256));
        assertFalse(RestartPolicy.shouldOffer("prompt",pending,null,pending.archiveSha256));
        assertTrue(RestartPolicy.shouldOffer("automatic",offer("c"),pending,pending.archiveSha256));
    }
}
