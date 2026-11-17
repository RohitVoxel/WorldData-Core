package net.wdc.core.world.governor;

public record GovernorMetricsSnapshot(
        int queueDepth,
        int maxQueueDepthObserved,
        int lastDrainWorkUnits,
        long lastDrainDurationNanos,
        TickBudget.Snapshot budget
) {
}
