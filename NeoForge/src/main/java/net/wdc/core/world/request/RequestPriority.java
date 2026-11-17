package net.wdc.core.world.request;

public enum RequestPriority {
    CRITICAL(0),
    HIGH(1),
    NORMAL(2),
    LOW(3);

    private final int tier;

    RequestPriority(int tier) {
        this.tier = tier;
    }

    public int tier() {
        return tier;
    }
}
