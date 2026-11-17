package net.wdc.core.api.world;

import net.wdc.core.api.internal.WorldStateSource;
import net.wdc.core.debug.WDCOps;
import net.wdc.core.debug.monitor.MonitorApi;
import net.wdc.core.debug.monitor.MonitorOutcome;
import net.wdc.core.debug.monitor.WDCApiMonitor;

import java.util.Optional;

public final class WorldApi {

    private final WorldStateSource source;

    public WorldApi(WorldStateSource source) {
        this.source = source == null ? WorldStateSource.UNAVAILABLE : source;
    }

    public Optional<WDCWorldSnapshot> snapshot(String dimensionId) {
        if (dimensionId == null || dimensionId.isEmpty()) throw new IllegalArgumentException("dimensionId must not be null or empty");
        WDCOps.query();
        Optional<WDCWorldSnapshot> found = source.snapshot(dimensionId);
        if (WDCApiMonitor.enabled) {
            WDCApiMonitor.hit(MonitorApi.WORLD_SNAPSHOT, found.isPresent() ? MonitorOutcome.LIVE : MonitorOutcome.UNAVAILABLE,
                    WDCApiMonitor.textKey(dimensionId));
        }
        return found;
    }
}
