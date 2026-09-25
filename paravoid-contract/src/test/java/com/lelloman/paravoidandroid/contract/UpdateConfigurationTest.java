package com.lelloman.paravoidandroid.contract;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateConfigurationTest {
    @Test public void restartBehaviorIsOptionalAndRestricted() throws Exception {
        UpdateConfiguration.validate(Collections.emptyMap());
        for(String behavior:new String[]{"manual","prompt","automatic"})
            UpdateConfiguration.validate(Collections.singletonMap("restartBehavior",behavior));
        assertThrows(ContractException.class,()->UpdateConfiguration.validate(Collections.singletonMap("restartBehavior","always")));
    }
}
