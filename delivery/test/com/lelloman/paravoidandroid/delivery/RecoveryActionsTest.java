package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.ContractException.Code;
import com.lelloman.paravoidandroid.contract.Protocol.*;

public final class RecoveryActionsTest {
    private static final ExpectedArchive ONE = new ExpectedArchive("one", 1, "m", "a", 1);
    private static final ExpectedArchive TWO = new ExpectedArchive("two", 2, "m", "b", 1);

    private static LifecycleSnapshot state(Availability availability, ExpectedArchive active,
            ExpectedArchive pending, boolean waiting, Code error) {
        return new LifecycleSnapshot(availability, active, pending, null, waiting, 1, 0, error);
    }

    public static void main(String[] args) {
        assert RecoveryActions.canOfferGenerationRetry(state(Availability.RECOVERY, ONE, null, false, Code.UNAVAILABLE));
        assert !RecoveryActions.canOfferGenerationRetry(state(Availability.RECOVERY, ONE, null, false, Code.INTEGRITY));
        assert !RecoveryActions.canOfferGenerationRetry(state(Availability.RECOVERY, ONE, TWO, false, Code.UNAVAILABLE));
        assert !RecoveryActions.canOfferGenerationRetry(state(Availability.RECOVERY, ONE, TWO, true, Code.UNAVAILABLE));
        assert !RecoveryActions.canOfferGenerationRetry(state(Availability.RECOVERY, null, null, false, Code.UNAVAILABLE));
        assert !RecoveryActions.canOfferGenerationRetry(state(Availability.RUNNABLE, ONE, null, false, null));
        assert !RecoveryActions.canOfferGenerationRetry(null);
        System.out.println("PASS conservative recovery action policy");
    }
}
