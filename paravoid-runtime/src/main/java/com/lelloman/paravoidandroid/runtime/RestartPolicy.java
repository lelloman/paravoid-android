package com.lelloman.paravoidandroid.runtime;

import com.lelloman.paravoidandroid.contract.Protocol.ExpectedArchive;

/** At most one policy-driven restart offer/attempt per staged archive; explicit calls remain available. */
final class RestartPolicy {
    static boolean shouldOffer(String behavior,ExpectedArchive pending,ExpectedArchive handled,String previous) {
        return (behavior.equals("automatic") || behavior.equals("prompt")) && pending!=null
            && !pending.equals(handled) && !pending.archiveSha256.equals(previous);
    }
}
