package net.wdc.core.world.ingestion;

import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.storage.ChunkKey;
import net.wdc.core.world.work.CoreThreads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class ClientChunkViewSource implements ChunkViewSource {

    @Override
    public MinecraftChunkView viewOf(WorldKey world, ChunkKey chunk) {
        CoreThreads.requireNotWorker("minecraft chunk lookup");
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().toString().equals(world.dimensionId())) return null;
        LevelChunk loaded = level.getChunkSource().getChunk(chunk.chunkX(), chunk.chunkZ(), ChunkStatus.FULL, false);
        return loaded == null ? null : new ClientChunkAdapter(world.dimensionId(), loaded, level);
    }
}
