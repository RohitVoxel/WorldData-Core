package net.wdc.core.api.internal;

import net.wdc.core.api.world.WDCWorldSnapshot;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.function.Supplier;

public final class ServerWorldStateSource implements WorldStateSource {

    private final Supplier<MinecraftServer> serverSupplier;

    public ServerWorldStateSource(Supplier<MinecraftServer> serverSupplier) {
        if (serverSupplier == null) throw new IllegalArgumentException("serverSupplier must not be null");
        this.serverSupplier = serverSupplier;
    }

    @Override
    public Optional<WDCWorldSnapshot> snapshot(String dimensionId) {
        MinecraftServer server = serverSupplier.get();
        if (server == null) return Optional.empty();

        ResourceKey<Level> dimension;
        try {
            dimension = DimensionIds.keyOf(dimensionId);
        } catch (Exception e) {
            return Optional.empty();
        }

        ServerLevel level = server.getLevel(dimension);
        if (level == null) return Optional.empty();

        return Optional.of(new WDCWorldSnapshot(
                dimensionId,
                level.getGameTime(),
                level.getDayTime(),
                level.isRaining(),
                level.isThundering(),
                level.getRainLevel(1.0f),
                level.getThunderLevel(1.0f),
                level.getMoonPhase(),
                false
        ));
    }
}
