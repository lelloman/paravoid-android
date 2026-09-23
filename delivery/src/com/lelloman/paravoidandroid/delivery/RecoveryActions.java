package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.ContractException.Code;
import com.lelloman.paravoidandroid.contract.Protocol.Availability;
import com.lelloman.paravoidandroid.contract.Protocol.LifecycleSnapshot;

/** Conservative controls derived from a lifecycle snapshot; lifecycle remains final authority. */
final class RecoveryActions {
    private RecoveryActions() {}

    static boolean canOfferGenerationRetry(LifecycleSnapshot state) {
        return state != null && state.availability == Availability.RECOVERY
            && state.active != null && state.error == Code.UNAVAILABLE
            && state.pending == null && !state.waitingForProcesses;
    }
}
