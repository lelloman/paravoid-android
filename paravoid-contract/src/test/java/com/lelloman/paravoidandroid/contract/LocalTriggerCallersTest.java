package com.lelloman.paravoidandroid.contract;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocalTriggerCallersTest {
    @Test public void optionalAndIndependentOfWebSocketPush() throws Exception {
        assertTrue(LocalTriggerCallers.read(null).isEmpty());
        String json="{\"com.store.one\":[\""+"a".repeat(64)+"\",\""+"b".repeat(64)+"\"],\"com.store.two\":[\""+"c".repeat(64)+"\"]}";
        assertEquals(2,LocalTriggerCallers.read(json).size());
        UpdateConfiguration.validate(Collections.singletonMap("localTriggerCallers",json));
    }
    @Test public void rejectsMalformedTrustAndDuplicateKeys() {
        String pin="\""+"a".repeat(64)+"\"";
        for(String json:new String[]{"[]","null","", "{\"bad\":["+pin+"]}",
                "{\"com.store\":[]}","{\"com.store\":42}","{\"com.store\":[\"abc\"]}",
                "{\"com.store\":["+pin+","+pin+"]}","{\"com.store\":["+pin+"],\"com.store\":["+pin+"]}"})
            assertThrows(json,ContractException.class,()->LocalTriggerCallers.read(json));
    }
}
