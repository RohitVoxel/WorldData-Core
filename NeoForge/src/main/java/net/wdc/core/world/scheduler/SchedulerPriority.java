package net.wdc.core.world.scheduler;

public enum SchedulerPriority {
    URGENT(0),
    AGED(1),
    HIGH(2),
    NORMAL(3),
    LOW(4);

    private final int tier;

    SchedulerPriority(int tier) {
        this.tier = tier;
    }

    public int tier() {
        return tier;
    }
}
