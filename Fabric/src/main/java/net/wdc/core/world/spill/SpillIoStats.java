package net.wdc.core.world.spill;

public record SpillIoStats(
        long spillQueued,
        long spillCompleted,
        long spillFailed,
        long spillDiscarded,
        long restoreQueued,
        long restoreCompleted,
        long restoreFailed,
        long restoreDiscarded,
        long restoreMissed,
        long restoreSuppressed,
        long deferred,
        int queueDepth,
        int inFlight,
        int queueCapacity
) {}
