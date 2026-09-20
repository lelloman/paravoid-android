package com.lelloman.paravoidcompat.os.contract;
import com.lelloman.paravoidcompat.os.contract.WireMessage;
import com.lelloman.paravoidcompat.os.contract.IProbeCallback;
import android.os.Bundle;
interface IProbe {
    WireMessage exchange(in WireMessage message, IProbeCallback callback);
    List<WireMessage> echoList(in List<WireMessage> messages);
    void reject();
    Bundle exchangeBundle(in Bundle request, boolean prepareLoader);
}
