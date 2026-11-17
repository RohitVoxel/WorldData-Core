package net.wdc.core.world.ingestion;

import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.storage.ChunkKey;
import net.wdc.core.world.work.CoreThreads;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.function.Supplier;

public final class ServerLoadedChunkProbe implements LoadedChunkProbe {

    private final Supplier<MinecraftServer> serverSupplier;

    public ServerLoadedChunkProbe(Supplier<MinecraftServer> serverSupplier) {
        if (serverSupplier == null) throw new IllegalArgumentException("serverSupplier must not be null");
        this.serverSupplier = serverSupplier;
    }

    @Override
    @SuppressWarnings("resource")
    public boolean isLoaded(WorldKey world, ChunkKey chunk) {
        CoreThreads.requireNotWorker("minecraft chunk probe");
        MinecraftServer server = serverSupplier.get();
        if (server == null) return false;
        ResourceLocation id = ResourceLocation.tryParse(world.dimensionId());
        if (id == null) return false;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        return level != null && level.getChunkSource().getChunkNow(chunk.chunkX(), chunk.chunkZ()) != null;
    }
}
