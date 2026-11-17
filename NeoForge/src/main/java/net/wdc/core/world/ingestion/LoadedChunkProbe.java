package net.wdc.core.world.ingestion;

import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.storage.ChunkKey;

@FunctionalInterface
public interface LoadedChunkProbe {

    boolean isLoaded(WorldKey world, ChunkKey chunk);
}
