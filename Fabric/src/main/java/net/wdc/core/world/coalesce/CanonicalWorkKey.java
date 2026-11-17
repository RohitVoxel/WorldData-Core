package net.wdc.core.world.coalesce;

import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.demand.DataLayer;
import net.wdc.core.world.request.RequestGeneration;
import net.wdc.core.world.storage.ChunkKey;

public record CanonicalWorkKey(WorldKey world, ChunkKey chunk, DataLayer layer, RequestGeneration generation) {

    public CanonicalWorkKey {
        if (world == null || chunk == null || layer == null || generation == null) {
            throw new IllegalArgumentException("world/chunk/layer/generation must not be null");
        }
    }
}
