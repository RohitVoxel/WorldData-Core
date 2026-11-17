package net.wdc.core.world.ingestion;

import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.storage.ChunkKey;
import net.wdc.core.world.work.CoreThreads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class ClientLoadedChunkProbe implements LoadedChunkProbe {

    @Override
    public boolean isLoaded(WorldKey world, ChunkKey chunk) {
        CoreThreads.requireNotWorker("minecraft chunk probe");
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().toString().equals(world.dimensionId())) return false;
        return level.getChunkSource().getChunk(chunk.chunkX(), chunk.chunkZ(), ChunkStatus.FULL, false) != null;
    }
}
