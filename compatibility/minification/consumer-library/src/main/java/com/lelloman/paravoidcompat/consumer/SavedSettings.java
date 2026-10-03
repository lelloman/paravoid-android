package com.lelloman.paravoidcompat.consumer;

/** Deliberately reached only by name; its serialization schema survives upgrades. */
public final class SavedSettings implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public int savedChoice;
    public String seedNonce;
    public SavedSettings() {}
}
