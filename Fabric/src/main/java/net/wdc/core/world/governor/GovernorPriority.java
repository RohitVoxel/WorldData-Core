package net.wdc.core.world.governor;

public enum GovernorPriority {
    ACTIVE_DEMAND(0),
    VISIBLE(1),
    MOVEMENT_DIRECTION(2),
    REQUEST_PRIORITY(3),
    DISTANCE(4),
    AGED(5),
    PREFETCH(6);

    private final int tier;

    GovernorPriority(int tier) {
        this.tier = tier;
    }

    public int tier() {
        return tier;
    }
}
