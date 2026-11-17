package net.wdc.core.api.internal;

import net.wdc.core.api.request.WDCLightInfo;
import net.wdc.core.api.request.WDCLightResult;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class ServerLightSource implements LightSource {

    private final Supplier<MinecraftServer> serverSupplier;
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private final ConcurrentHashMap<String, LevelDarkening> darkening = new ConcurrentHashMap<>();
    private MinecraftServer lastServer;
    private String lastDimensionId;
    private ServerLevel lastLevel;
    private LevelDarkening lastDarkening;

    public ServerLightSource(Supplier<MinecraftServer> serverSupplier) {
        if (serverSupplier == null) throw new IllegalArgumentException("serverSupplier must not be null");
        this.serverSupplier = serverSupplier;
    }

    @Override
    public Optional<WDCLightInfo> lightAt(String dimensionId, int blockX, int blockY, int blockZ) {
        MinecraftServer server = serverSupplier.get();
        if (server == null) return Optional.empty();
        if (Thread.currentThread() != server.getRunningThread()) {
            return LightSnapshots.shared().read(dimensionId, blockX, blockY, blockZ);
        }
        ServerLevel level = liveLevel(server, dimensionId);
        if (level == null) return Optional.empty();
        WDCLightInfo info = sample(level, blockX, blockY, blockZ);
        return info == null ? Optional.empty() : Optional.of(info);
    }

    @Override
    public WDCLightResult read(String dimensionId, int blockX, int blockY, int blockZ) {
        MinecraftServer server = serverSupplier.get();
        if (server == null) return WDCLightResult.NO_WORLD;
        if (Thread.currentThread() != server.getRunningThread()) {
            return LightSnapshots.shared().readResult(dimensionId, blockX, blockY, blockZ);
        }
        ServerLevel level = liveLevel(server, dimensionId);
        if (level == null) return WDCLightResult.NO_WORLD;
        WDCLightInfo info = sample(level, blockX, blockY, blockZ);
        return info == null ? WDCLightResult.UNLOADED : WDCLightResult.present(info);
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimensionId) {
        ResourceKey<Level> dimension;
        try {
            dimension = DimensionIds.keyOf(dimensionId);
        } catch (RuntimeException invalid) {
            return null;
        }
        return server.getLevel(dimension);
    }

    private ServerLevel liveLevel(MinecraftServer server, String dimensionId) {
        ServerLevel cached = lastLevel;
        if (cached != null && server == lastServer && dimensionId.equals(lastDimensionId)) return cached;
        ServerLevel level = levelOf(server, dimensionId);
        if (level == null) return null;
        LevelDarkening memo = darkening.computeIfAbsent(dimensionId, id -> new LevelDarkening());
        lastServer = server;
        lastDimensionId = dimensionId;
        lastDarkening = memo;
        lastLevel = level;
        return level;
    }

    private WDCLightInfo sample(ServerLevel level, int blockX, int blockY, int blockZ) {
        BlockPos pos = scratch.set(blockX, blockY, blockZ);
        if (!level.isLoaded(pos)) return null;
        int sky = level.getBrightness(LightLayer.SKY, pos);
        int block = level.getBrightness(LightLayer.BLOCK, pos);
        int dark = lastDarkening.of(level);
        int effectiveSky = SkyDarkening.effectiveSkyLight(sky, dark);
        return new WDCLightInfo(sky, block, dark, effectiveSky, Math.max(block, effectiveSky));
    }
}
