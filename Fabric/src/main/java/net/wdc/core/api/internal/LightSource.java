package net.wdc.core.api.internal;

import net.wdc.core.api.request.WDCLightInfo;
import net.wdc.core.api.request.WDCLightResult;

import java.util.Optional;

public interface LightSource {

    LightSource UNAVAILABLE = (dimensionId, blockX, blockY, blockZ) -> Optional.empty();

    Optional<WDCLightInfo> lightAt(String dimensionId, int blockX, int blockY, int blockZ);

    default WDCLightResult read(String dimensionId, int blockX, int blockY, int blockZ) {
        Optional<WDCLightInfo> info = lightAt(dimensionId, blockX, blockY, blockZ);
        return info.isPresent() ? WDCLightResult.present(info.get()) : WDCLightResult.UNAVAILABLE;
    }
}
