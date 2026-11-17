package net.wdc.core.api.request;

import net.wdc.core.debug.WDCOps;
import net.wdc.core.debug.monitor.MonitorApi;
import net.wdc.core.debug.monitor.MonitorOutcome;
import net.wdc.core.debug.monitor.WDCApiMonitor;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.DataFreshness;
import net.wdc.core.world.cache.DataResult;
import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.demand.WDCDemandService;
import net.wdc.core.world.demand.WDCDemandServiceProvider;
import net.wdc.core.world.demand.ChunkDemand;
import net.wdc.core.world.demand.DataLayer;
import net.wdc.core.world.demand.QueryPriority;
import net.wdc.core.world.demand.RetentionPolicy;
import net.wdc.core.world.governor.DrainReport;
import net.wdc.core.world.governor.IngestionGovernor;
import net.wdc.core.world.governor.IngestionGovernorProvider;
import net.wdc.core.world.ingestion.LoadedChunkProbe;
import net.wdc.core.world.ingestion.LoadedChunkProbeProvider;
import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.ChunkKey;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;

import java.util.Set;

public final class RequestsApi {

    private static final Set<DataLayer> READ_LAYERS = Set.of(DataLayer.BLOCK_STATES);

    private final WDCCache cache;
    private final GlobalBlockStateRegistry registry;
    private final LoadedChunkProbe probe;

    public RequestsApi(WDCCache cache, GlobalBlockStateRegistry registry) {
        this(cache, registry, LoadedChunkProbeProvider.getIfPresent());
    }

    public RequestsApi(WDCCache cache, GlobalBlockStateRegistry registry, LoadedChunkProbe probe) {
        this.cache = cache;
        this.registry = registry;
        this.probe = probe;
    }

    public WDCChunkResult chunk(String dimensionId, int chunkX, int chunkZ) {
        WDCOps.query();
        requireCache();
        if (dimensionId == null) throw new IllegalArgumentException("dimensionId must not be null");
        WorldKey world = cache.currentWorldKey(dimensionId);
        DataResult<WDCChunk> result = cache.query(world, chunkX, chunkZ);
        WDCAcquisitionStatus status = statusOf(world, chunkX, chunkZ, result.status());
        WDCChunkResult built = new WDCChunkResult(chunkX, chunkZ, toFreshness(result.status()), result.value(), registry,
                WDCDemandServiceProvider.getIfPresent(), world, status);
        if (WDCApiMonitor.enabled) WDCApiMonitor.hit(MonitorApi.CHUNK, built.monitorOutcome(), WDCApiMonitor.chunkKey(chunkX, chunkZ));
        return built;
    }

    public WDCChunkResult peek(String dimensionId, int chunkX, int chunkZ) {
        WDCOps.query();
        requireCache();
        WorldKey world = cache.currentWorldKey(dimensionId);
        DataResult<WDCChunk> result = cache.query(world, chunkX, chunkZ);
        WDCDemandService demandService = WDCDemandServiceProvider.getIfPresent();
        WDCChunkResult built = new WDCChunkResult(chunkX, chunkZ, toFreshness(result.status()), result.value(), registry, demandService,
                world, defaultStatus(result.status()));
        if (WDCApiMonitor.enabled) WDCApiMonitor.hit(MonitorApi.PEEK, built.monitorOutcome(), WDCApiMonitor.chunkKey(chunkX, chunkZ));
        return built;
    }

    public WDCAvailability availability(String dimensionId, int chunkX, int chunkZ) {
        WDCOps.query();
        WDCAvailability answer = availabilityOf(dimensionId, chunkX, chunkZ);
        if (WDCApiMonitor.enabled) WDCApiMonitor.hit(MonitorApi.AVAILABILITY, availabilityOutcome(answer), WDCApiMonitor.chunkKey(chunkX, chunkZ));
        return answer;
    }

    private static MonitorOutcome availabilityOutcome(WDCAvailability answer) {
        return switch (answer) {
            case LIVE -> MonitorOutcome.LIVE;
            case STALE -> MonitorOutcome.STALE;
            case ACQUIRING -> MonitorOutcome.PENDING;
            default -> MonitorOutcome.UNAVAILABLE;
        };
    }

    private WDCAvailability availabilityOf(String dimensionId, int chunkX, int chunkZ) {
        requireCache();
        WorldKey world = cache.currentWorldKey(dimensionId);
        DataFreshness freshness = cache.freshnessOf(world, chunkX, chunkZ);
        if (freshness == DataFreshness.LIVE) return WDCAvailability.LIVE;
        if (freshness == DataFreshness.STALE) return WDCAvailability.STALE;
        ChunkKey chunk = new ChunkKey(chunkX, chunkZ);
        if (ingestPending(world, chunk)) return WDCAvailability.ACQUIRING;
        LoadedChunkProbe loaded = probe;
        if (loaded != null && loaded.isLoaded(world, chunk)) {
            WDCMetrics.increment(WDCMetrics.Counter.MC_HELD_NOT_IN_CORE);
            WDCDemandService demand = WDCDemandServiceProvider.getIfPresent();
            if (demand == null) return WDCAvailability.UNAVAILABLE;
            demand.requestCurrent(world, ChunkDemand.of(chunk.chunkX(), chunk.chunkZ(), READ_LAYERS, QueryPriority.HIGH,
                    RetentionPolicy.AUTO));
            return WDCAvailability.ACQUIRING;
        }
        return WDCAvailability.UNAVAILABLE;
    }

    public DrainWaitingResult drainWaiting(DrainWaitingRequest request) {
        if (WDCApiMonitor.enabled) WDCApiMonitor.hit(MonitorApi.DRAIN_WAITING, WDCApiMonitor.NO_KEY);
        if (request == null) throw new IllegalArgumentException("request must not be null");
        IngestionGovernor governor = IngestionGovernorProvider.get();
        if (governor == null) throw new IllegalStateException("WorldData has not finished initializing on this side yet");
        DrainReport report = governor.drainWaiting(request.maxItems());
        return new DrainWaitingResult(report.processed(), report.remainingWaiting(), report.budgetExhausted(),
                report.queueEmpty(), report.blocked());
    }

    private WDCAcquisitionStatus statusOf(WorldKey world, int chunkX, int chunkZ, DataFreshness freshness) {
        if (freshness != DataFreshness.UNAVAILABLE) return WDCAcquisitionStatus.DELIVERED;
        ChunkKey chunk = new ChunkKey(chunkX, chunkZ);
        LoadedChunkProbe loaded = probe;
        if (loaded == null) return WDCAcquisitionStatus.NOT_REQUESTED;
        if (ingestPending(world, chunk)) return WDCAcquisitionStatus.PENDING;
        if (!loaded.isLoaded(world, chunk)) return WDCAcquisitionStatus.DATA_UNLOADED;
        WDCMetrics.increment(WDCMetrics.Counter.MC_HELD_NOT_IN_CORE);
        WDCDemandService demand = WDCDemandServiceProvider.getIfPresent();
        if (demand == null) return WDCAcquisitionStatus.NOT_AVAILABLE;
        demand.requestCurrent(world, ChunkDemand.of(chunk.chunkX(), chunk.chunkZ(), READ_LAYERS, QueryPriority.HIGH,
                RetentionPolicy.AUTO));
        return demand.isAcquiring(world, chunk) ? WDCAcquisitionStatus.PENDING : WDCAcquisitionStatus.NOT_AVAILABLE;
    }

    private static boolean ingestPending(WorldKey world, ChunkKey chunk) {
        IngestionGovernor governor = IngestionGovernorProvider.get();
        if (governor != null && governor.isQueued(world, chunk)) return true;
        WDCDemandService demand = WDCDemandServiceProvider.getIfPresent();
        return demand != null && demand.isAcquiring(world, chunk);
    }

    private void requireCache() {
        if (cache == null) throw new IllegalStateException("WorldData has not finished initializing on this side yet");
    }

    private static WDCFreshness toFreshness(DataFreshness status) {
        return switch (status) {
            case LIVE -> WDCFreshness.LIVE;
            case STALE -> WDCFreshness.STALE;
            case UNAVAILABLE -> WDCFreshness.UNAVAILABLE;
        };
    }

    private static WDCAcquisitionStatus defaultStatus(DataFreshness status) {
        return status == DataFreshness.UNAVAILABLE ? WDCAcquisitionStatus.NOT_REQUESTED : WDCAcquisitionStatus.DELIVERED;
    }
}
