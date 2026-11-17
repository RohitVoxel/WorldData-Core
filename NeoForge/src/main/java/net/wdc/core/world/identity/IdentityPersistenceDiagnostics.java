package net.wdc.core.world.identity;

public record IdentityPersistenceDiagnostics(
        int queuedIoTasks,
        long writesCompleted,
        long writesDiscarded,
        long loadsCompleted,
        long corruptedReadsDetected,
        boolean ioThreadActive
) {}
