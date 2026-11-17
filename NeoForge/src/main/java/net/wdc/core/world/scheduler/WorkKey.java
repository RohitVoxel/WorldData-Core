package net.wdc.core.world.scheduler;

import net.wdc.core.world.demand.DataLayer;
import net.wdc.core.world.request.RequestGeneration;
import net.wdc.core.world.request.RequestId;
import net.wdc.core.world.storage.ChunkKey;

import java.util.Set;

public record WorkKey(RequestGeneration generation, String dimensionId, ChunkKey chunk, Set<DataLayer> layers, RequestId requestId) {

    public WorkKey {
        if (generation == null) throw new IllegalArgumentException("generation must not be null");
        if (dimensionId == null || dimensionId.isEmpty()) throw new IllegalArgumentException("dimensionId must not be null or empty");
        if (chunk == null) throw new IllegalArgumentException("chunk must not be null");
        if (layers == null || layers.isEmpty()) throw new IllegalArgumentException("layers must not be null or empty");
        if (requestId == null) throw new IllegalArgumentException("requestId must not be null");
        layers = Set.copyOf(layers);
    }
}
