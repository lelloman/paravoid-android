package com.lelloman.paravoidandroid.contract;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateConfigurationTest {
    @Test public void backgroundConnectionRequiresEnabledWebSocket() throws Exception {
        java.util.Map<String,String> config=new java.util.HashMap<>();
        config.put("pushBackgroundConnection","true");
        assertThrows(ContractException.class,()->UpdateConfiguration.validate(config));
        config.put("pushEnabled","true"); config.put("pushTransportClass","example.Adapter");
        assertThrows(ContractException.class,()->UpdateConfiguration.validate(config));
        config.put("pushWebSocketUrl","wss://updates.example/v1/events");
        UpdateConfiguration.validate(config);
        config.put("pushBackgroundConnection","yes");
        assertThrows(ContractException.class,()->UpdateConfiguration.validate(config));
        config.put("pushBackgroundConnection","false"); config.remove("pushWebSocketUrl");
        UpdateConfiguration.validate(config);
    }
    @Test public void restartBehaviorIsOptionalAndRestricted() throws Exception {
        UpdateConfiguration.validate(Collections.emptyMap());
        for(String behavior:new String[]{"manual","prompt","automatic"})
            UpdateConfiguration.validate(Collections.singletonMap("restartBehavior",behavior));
        assertThrows(ContractException.class,()->UpdateConfiguration.validate(Collections.singletonMap("restartBehavior","always")));
    }
}
