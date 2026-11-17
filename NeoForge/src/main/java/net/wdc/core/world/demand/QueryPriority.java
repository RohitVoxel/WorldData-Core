package net.wdc.core.world.demand;

public enum QueryPriority {
    HIGH(0),
    NORMAL(1),
    LOW(2),
    BACKGROUND(3);

    private final int schedulerTier;

    QueryPriority(int schedulerTier) {
        this.schedulerTier = schedulerTier;
    }

    public int schedulerTier() {
        return schedulerTier;
    }
}
