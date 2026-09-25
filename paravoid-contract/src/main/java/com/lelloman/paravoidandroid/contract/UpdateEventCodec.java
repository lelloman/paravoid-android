package com.lelloman.paravoidandroid.contract;

import java.util.*;
import com.lelloman.paravoidandroid.contract.Protocol.RequestScope;
import static com.lelloman.paravoidandroid.contract.SignedMetadataVerifier.*;
import static com.lelloman.paravoidandroid.contract.ContractException.Code.*;

/** Transport-independent wake-up hints. Verification/admission still requires signed discovery. */
public final class UpdateEventCodec {
    public static final int MAX_BYTES=4096;
    private UpdateEventCodec() {}
    public static String verifyScope(byte[] bytes,RequestScope scope) throws ContractException {
        Map<String,Object> event=object(StrictJson.parse(bytes,MAX_BYTES));
        fields(event,"version type applicationId shellContractId channel eventId"); version(event,"version");
        if(!"updates_changed".equals(string(event,"type")) || !scope.applicationId.equals(string(event,"applicationId"))
                || !scope.shellContractId.equals(hash(event,"shellContractId")) || !scope.channel.equals(identifier(event,"channel")))
            throw fail(INCOMPATIBLE,"Update event scope mismatch");
        return identifier(event,"eventId");
    }
    public static byte[] subscription(RequestScope scope) throws ContractException {
        Map<String,Object> event=new LinkedHashMap<>(); event.put("version",1); event.put("type","subscribe");
        event.put("applicationId",scope.applicationId); event.put("shellContractId",scope.shellContractId); event.put("channel",scope.channel);
        return StrictJson.canonical(event);
    }
}
