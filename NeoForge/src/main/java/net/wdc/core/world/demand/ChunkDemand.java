package net.wdc.core.world.demand;

import net.wdc.core.world.storage.ChunkKey;
import java.util.Set;

public record ChunkDemand(
        ChunkKey chunk,
        Set<DataLayer> layers,
        QueryPriority priority,
        RetentionPolicy retention) {

    public ChunkDemand {
        if (chunk == null) throw new IllegalArgumentException("chunk must not be null");
        if (layers == null || layers.isEmpty()) throw new IllegalArgumentException("layers must not be null or empty");
        if (priority == null) throw new IllegalArgumentException("priority must not be null");
        if (retention == null) throw new IllegalArgumentException("retention must not be null");
        layers = Set.copyOf(layers);
    }

    public static ChunkDemand of(int chunkX, int chunkZ, Set<DataLayer> layers, QueryPriority priority, RetentionPolicy retention) {
        return new ChunkDemand(new ChunkKey(chunkX, chunkZ), layers, priority, retention);
    }

    public int chunkX() { return chunk.chunkX(); }
    public int chunkZ() { return chunk.chunkZ(); }
}
