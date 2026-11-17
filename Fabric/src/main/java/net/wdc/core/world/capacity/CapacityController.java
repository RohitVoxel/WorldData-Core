package net.wdc.core.world.capacity;

import net.wdc.core.config.CapacityConfig;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.demand.WDCDemandService;
import net.wdc.core.world.governor.IngestionGovernor;
import net.wdc.core.world.governor.IngestionPerformanceConfig;
import net.wdc.core.world.request.RequestManager;
import net.wdc.core.world.request.RequestRecord;
import net.wdc.core.world.spill.DelayedSpillPolicy;
import net.wdc.core.world.storage.ChunkKey;

import java.util.List;

public final class CapacityController {

    private static volatile CapacityConfig runtime = new CapacityConfig();

    private CapacityController() {}

    public static void applyStartup(CapacityConfig capacity, WDCCache cache, IngestionGovernor governor,
                                    DelayedSpillPolicy spill, WDCDemandService demand) {
        applyStartup(capacity, cache, governor, spill, demand, null);
    }

    public static void applyStartup(CapacityConfig capacity, WDCCache cache, IngestionGovernor governor,
                                    DelayedSpillPolicy spill, WDCDemandService demand, RequestManager requests) {
        CapacityConfig cfg = capacity == null ? new CapacityConfig() : capacity.copy();
        cfg.validate();
        runtime = cfg;
        if (cache != null) apply(cfg, cache, spill);
        if (governor == null) return;
        governor.setDemandProbe(demandProbe(demand, requests));
        IngestionPerformanceConfig performance = governor.config();
        if (performance.queueCapacity > cfg.ingestQueueLengthMax || performance.liveLimit > cfg.liveMaxCeiling) {
            IngestionPerformanceConfig clamped = performance.copy();
            clamped.queueCapacity = Math.min(clamped.queueCapacity, cfg.ingestQueueLengthMax);
            clamped.liveLimit = Math.min(clamped.liveLimit, cfg.liveMaxCeiling);
            governor.applyConfig(clamped);
        }
    }

    public static void update(CapacityConfig next, WDCCache cache, DelayedSpillPolicy spill) {
        if (next == null) throw new IllegalArgumentException("config must not be null");
        next.validate();
        runtime = next.copy();
        apply(runtime, cache, spill);
    }

    private static void apply(CapacityConfig cfg, WDCCache cache, DelayedSpillPolicy spill) {
        if (cache != null) {
            cache.config().setMaxStaleChunksPerDimension(cfg.staleMax);
            cache.config().setEvictionBatch(cfg.evictionBatch);
        }
        if (spill != null) spill.setSpillQueueCapacity(cfg.spillQueueLength);
    }

    public static CapacityConfig runtime() { return runtime.copy(); }

    public static DemandProbe demandProbe(WDCDemandService demand) {
        return demandProbe(demand, null);
    }

    public static DemandProbe demandProbe(WDCDemandService demand, RequestManager requests) {
        return new DemandProbe() {
            @Override
            public boolean isNeeded(WorldKey world, ChunkKey chunk) {
                if (demand != null && demand.interestedQueryCount(world, chunk) > 0) return true;
                return requests != null && coveredByActiveRequest(requests.activeRequests(), world, chunk);
            }

            @Override
            public DemandProbe snapshot() {
                List<RequestRecord> active = requests == null ? List.of() : requests.activeRequests();
                return (world, chunk) -> (demand != null && demand.interestedQueryCount(world, chunk) > 0)
                        || coveredByActiveRequest(active, world, chunk);
            }
        };
    }

    private static boolean coveredByActiveRequest(List<RequestRecord> active, WorldKey world, ChunkKey chunk) {
        for (RequestRecord record : active) {
            if (record.scope().dimensionId().equals(world.dimensionId())
                    && record.scope().contains(chunk.chunkX(), chunk.chunkZ())) {
                return true;
            }
        }
        return false;
    }
}
