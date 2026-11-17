package net.wdc.core.api.request;

public record DrainWaitingRequest(int maxItems) {

    public static final int DEFAULT_MAX_ITEMS = 1000;

    public DrainWaitingRequest {
        if (maxItems < 1) throw new IllegalArgumentException("maxItems must be >= 1");
    }

    public static DrainWaitingRequest defaults() {
        return new DrainWaitingRequest(DEFAULT_MAX_ITEMS);
    }

    public static DrainWaitingRequest upTo(int maxItems) {
        return new DrainWaitingRequest(maxItems);
    }
}
