package com.lelloman.paravoidandroid.recovery;

/** A button of the recovery screen. */
public final class RecoveryAction {
    /** Passed back to {@link RecoveryActionProvider#perform}; not shown. */
    public final String id;
    /** Button text, already in the user's language. */
    public final String label;
    /** When set, the shell asks the user to confirm this text before performing the action. */
    public final String confirmation;

    public RecoveryAction(String id, String label) { this(id, label, null); }
    public RecoveryAction(String id, String label, String confirmation) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("id required");
        if (label == null || label.isEmpty()) throw new IllegalArgumentException("label required");
        this.id = id; this.label = label; this.confirmation = confirmation;
    }
}
