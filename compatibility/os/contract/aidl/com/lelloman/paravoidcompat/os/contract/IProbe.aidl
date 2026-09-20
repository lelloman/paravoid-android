package com.lelloman.paravoidcompat.os.contract;
import com.lelloman.paravoidcompat.os.contract.WireMessage;
import com.lelloman.paravoidcompat.os.contract.IProbeCallback;
interface IProbe {
    WireMessage exchange(in WireMessage message, IProbeCallback callback);
    List<WireMessage> echoList(in List<WireMessage> messages);
    void reject();
}
