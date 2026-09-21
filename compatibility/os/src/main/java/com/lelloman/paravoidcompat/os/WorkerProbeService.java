package com.lelloman.paravoidcompat.os;

/** Same wire contract, but an independent Application and payload loader. */
public final class WorkerProbeService extends BinderProbeService {
    @Override protected String reportName() { return "os-worker"; }
}
