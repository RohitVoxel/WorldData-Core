package net.wdc.core.world.ingestion;

import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.storage.ChunkKey;
import net.wdc.core.world.work.CoreThreads;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.function.Supplier;

public final class ServerChunkViewSource implements ChunkViewSource {

    private final Supplier<MinecraftServer> serverSupplier;

    public ServerChunkViewSource(Supplier<MinecraftServer> serverSupplier) {
        if (serverSupplier == null) throw new IllegalArgumentException("serverSupplier must not be null");
        this.serverSupplier = serverSupplier;
    }

    @Override
    public MinecraftChunkView viewOf(WorldKey world, ChunkKey chunk) {
        CoreThreads.requireNotWorker("minecraft chunk lookup");
        MinecraftServer server = serverSupplier.get();
        if (server == null) return null;
        ResourceLocation id = ResourceLocation.tryParse(world.dimensionId());
        if (id == null) return null;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) return null;
        LevelChunk loaded = level.getChunkSource().getChunkNow(chunk.chunkX(), chunk.chunkZ());
        return loaded == null ? null : new ServerChunkAdapter(world.dimensionId(), loaded, level);
    }
}
