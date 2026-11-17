package net.wdc.core.world.coalesce;

public enum CanonicalWorkState {
    NEW,
    QUEUED,
    IN_FLIGHT,
    AVAILABLE,
    FAILED,
    OBSOLETE;

    public boolean isTerminal() {
        return this == AVAILABLE || this == FAILED || this == OBSOLETE;
    }
}
