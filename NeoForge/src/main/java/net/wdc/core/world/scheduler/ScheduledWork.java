package net.wdc.core.world.scheduler;

import net.wdc.core.world.demand.DataLayer;
import net.wdc.core.world.request.RequestGeneration;
import net.wdc.core.world.request.RequestId;
import net.wdc.core.world.storage.ChunkKey;

import java.util.Set;

public record ScheduledWork(
        WorkKey key,
        RequestId requestId,
        RequestGeneration generation,
        SchedulerPriority priority,
        long enqueuedAtTick
) {

    public ScheduledWork {
        if (key == null || requestId == null || generation == null || priority == null) {
            throw new IllegalArgumentException("args must not be null");
        }
    }

    public String dimensionId() { return key.dimensionId(); }
    public ChunkKey chunk() { return key.chunk(); }
    public Set<DataLayer> layers() { return key.layers(); }
}
