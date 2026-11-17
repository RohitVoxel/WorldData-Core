package net.wdc.core.api.internal;

import net.wdc.core.api.request.WDCLightInfo;
import net.wdc.core.api.request.WDCLightResult;

import java.util.Optional;

public final class ClientFirstLightSource implements LightSource {

    private final LightSource client;
    private final LightSource fallback;

    public ClientFirstLightSource(LightSource client, LightSource fallback) {
        this.client = client;
        this.fallback = fallback;
    }

    public boolean uses(LightSource client, LightSource fallback) {
        return this.client == client && this.fallback == fallback;
    }

    @Override
    public Optional<WDCLightInfo> lightAt(String dimensionId, int blockX, int blockY, int blockZ) {
        Optional<WDCLightInfo> fromClient = client.lightAt(dimensionId, blockX, blockY, blockZ);
        return fromClient.isPresent() ? fromClient : fallback.lightAt(dimensionId, blockX, blockY, blockZ);
    }

    @Override
    public WDCLightResult read(String dimensionId, int blockX, int blockY, int blockZ) {
        WDCLightResult fromClient = client.read(dimensionId, blockX, blockY, blockZ);
        return fromClient.isPresent() ? fromClient : fallback.read(dimensionId, blockX, blockY, blockZ);
    }
}
