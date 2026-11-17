package net.wdc.core.api.internal;

import net.wdc.core.api.world.WDCWorldSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.Optional;

public final class ClientWorldStateSource implements WorldStateSource {

    @Override
    public Optional<WDCWorldSnapshot> snapshot(String dimensionId) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return Optional.empty();
        if (!DimensionIds.nameOf(level.dimension()).equals(dimensionId)) return Optional.empty();

        return Optional.of(new WDCWorldSnapshot(
                dimensionId,
                level.getGameTime(),
                level.getDayTime(),
                level.isRaining(),
                level.isThundering(),
                level.getRainLevel(1.0f),
                level.getThunderLevel(1.0f),
                level.getMoonPhase(),
                true
        ));
    }
}
