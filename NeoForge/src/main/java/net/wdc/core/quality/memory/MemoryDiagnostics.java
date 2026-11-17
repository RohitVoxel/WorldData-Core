package net.wdc.core.quality.memory;

public record MemoryDiagnostics(
        int queuedIoTasks,
        int pendingReclaimEntries,
        long writesCompleted,
        long loadsCompleted,
        boolean ioThreadActive
) {}
