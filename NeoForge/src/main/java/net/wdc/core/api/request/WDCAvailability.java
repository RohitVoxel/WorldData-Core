package net.wdc.core.api.request;

public enum WDCAvailability {
    LIVE,
    STALE,
    ACQUIRING,
    UNAVAILABLE;

    public boolean isUsable() { return this == LIVE || this == STALE; }
}
