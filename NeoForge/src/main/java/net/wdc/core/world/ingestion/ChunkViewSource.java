package net.wdc.core.world.ingestion;

import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.storage.ChunkKey;

@FunctionalInterface
public interface ChunkViewSource {

    MinecraftChunkView viewOf(WorldKey world, ChunkKey chunk);
}
