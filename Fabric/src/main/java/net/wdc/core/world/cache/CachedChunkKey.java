package net.wdc.core.world.cache;

import net.wdc.core.world.storage.ChunkKey;

public record CachedChunkKey(WorldKey world, ChunkKey chunk) {

    public CachedChunkKey {
        if (world == null) throw new IllegalArgumentException("world must not be null");
        if (chunk == null) throw new IllegalArgumentException("chunk must not be null");
    }

    public static CachedChunkKey of(WorldKey world, int chunkX, int chunkZ) {
        return new CachedChunkKey(world, new ChunkKey(chunkX, chunkZ));
    }
}
