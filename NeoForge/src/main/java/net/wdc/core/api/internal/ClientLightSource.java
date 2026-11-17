package net.wdc.core.api.internal;

import net.wdc.core.api.request.WDCLightInfo;
import net.wdc.core.api.request.WDCLightResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

import java.util.Optional;

public final class ClientLightSource implements LightSource {

    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private final LevelDarkening darkening = new LevelDarkening();

    @Override
    public Optional<WDCLightInfo> lightAt(String dimensionId, int blockX, int blockY, int blockZ) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) return Optional.empty();
        ClientLevel level = client.level;
        if (level == null || !DimensionIds.nameOf(level.dimension()).equals(dimensionId)) return Optional.empty();
        WDCLightInfo info = sample(level, blockX, blockY, blockZ);
        return info == null ? Optional.empty() : Optional.of(info);
    }

    @Override
    public WDCLightResult read(String dimensionId, int blockX, int blockY, int blockZ) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) return WDCLightResult.UNAVAILABLE;
        ClientLevel level = client.level;
        if (level == null || !DimensionIds.nameOf(level.dimension()).equals(dimensionId)) return WDCLightResult.NO_WORLD;
        WDCLightInfo info = sample(level, blockX, blockY, blockZ);
        return info == null ? WDCLightResult.UNLOADED : WDCLightResult.present(info);
    }

    private WDCLightInfo sample(ClientLevel level, int blockX, int blockY, int blockZ) {
        BlockPos pos = scratch.set(blockX, blockY, blockZ);
        if (!level.isLoaded(pos)) return null;
        int sky = level.getBrightness(LightLayer.SKY, pos);
        int block = level.getBrightness(LightLayer.BLOCK, pos);
        int dark = darkening.of(level);
        int effectiveSky = SkyDarkening.effectiveSkyLight(sky, dark);
        return new WDCLightInfo(sky, block, dark, effectiveSky, Math.max(block, effectiveSky));
    }
}
