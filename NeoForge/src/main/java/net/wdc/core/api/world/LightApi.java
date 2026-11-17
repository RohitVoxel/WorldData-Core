package net.wdc.core.api.world;

import net.wdc.core.api.internal.LightSource;
import net.wdc.core.api.request.WDCLightInfo;
import net.wdc.core.api.request.WDCLightResult;
import net.wdc.core.debug.WDCOps;
import net.wdc.core.debug.monitor.MonitorApi;
import net.wdc.core.debug.monitor.MonitorOutcome;
import net.wdc.core.debug.monitor.WDCApiMonitor;

import java.util.Optional;

public final class LightApi {

    private final LightSource source;

    public LightApi(LightSource source) {
        this.source = source == null ? LightSource.UNAVAILABLE : source;
    }

    public LightSource source() {
        return source;
    }

    public Optional<WDCLightInfo> lightAt(String dimensionId, int blockX, int blockY, int blockZ) {
        requireDimension(dimensionId);
        WDCOps.query();
        Optional<WDCLightInfo> found = source.lightAt(dimensionId, blockX, blockY, blockZ);
        if (WDCApiMonitor.enabled) {
            WDCApiMonitor.hit(MonitorApi.LIGHT_AT, found.isPresent() ? MonitorOutcome.LIVE : MonitorOutcome.UNAVAILABLE,
                    WDCApiMonitor.posKey(blockX, blockY, blockZ));
        }
        return found;
    }

    public WDCLightResult read(String dimensionId, int blockX, int blockY, int blockZ) {
        requireDimension(dimensionId);
        WDCOps.query();
        WDCLightResult result = source.read(dimensionId, blockX, blockY, blockZ);
        if (WDCApiMonitor.enabled) {
            WDCApiMonitor.hit(MonitorApi.LIGHT_READ, lightOutcome(result), WDCApiMonitor.posKey(blockX, blockY, blockZ));
        }
        return result;
    }

    private static MonitorOutcome lightOutcome(WDCLightResult result) {
        if (result == null) return MonitorOutcome.UNAVAILABLE;
        return switch (result.status()) {
            case PRESENT -> MonitorOutcome.LIVE;
            case UNLOADED -> MonitorOutcome.UNLOADED;
            case PENDING -> MonitorOutcome.PENDING;
            default -> MonitorOutcome.UNAVAILABLE;
        };
    }

    private static void requireDimension(String dimensionId) {
        if (dimensionId == null || dimensionId.isEmpty()) throw new IllegalArgumentException("dimensionId must not be null or empty");
    }
}
