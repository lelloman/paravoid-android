package com.lelloman.paravoidcompat.os.contract;
import com.lelloman.paravoidcompat.os.contract.WireMessage;
oneway interface IProbeCallback {
    void received(in WireMessage message);
}
