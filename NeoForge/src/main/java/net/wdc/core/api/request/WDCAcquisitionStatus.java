package net.wdc.core.api.request;

public enum WDCAcquisitionStatus {
    NOT_REQUESTED,
    PENDING,
    DELIVERED,
    NOT_AVAILABLE,
    DATA_UNLOADED;

    public boolean isFinal() {
        return this != PENDING && this != NOT_REQUESTED;
    }
}
