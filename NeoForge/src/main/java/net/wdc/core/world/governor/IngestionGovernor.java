package net.wdc.core.world.governor;

import net.wdc.core.config.BudgetConfig;
import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.debug.CoreCpuMeter;
import net.wdc.core.debug.CoreMemoryMeter;
import net.wdc.core.debug.WDCOps;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.DataResult;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.capacity.DemandProbe;
import net.wdc.core.world.capacity.LiveLimit;
import net.wdc.core.world.ingestion.ChunkIngestionService;
import net.wdc.core.world.ingestion.MinecraftChunkView;
import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.ChunkKey;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongToIntFunction;

public final class IngestionGovernor {

    private final WDCCache cache;
    private final ChunkIngestionService ingestionService;
    private final TickBudget tickBudget = new TickBudget();
    private final MovementSpeedTracker movementTracker = new MovementSpeedTracker();
    private final CoreCpuMeter cpuMeter = new CoreCpuMeter();
    private static final int MEMORY_DEMAND_TICKS = 1200;

    private final CoreMemoryMeter memoryMeter = new CoreMemoryMeter();
    private volatile int memoryDemandTicks;
    private boolean memoryActive;

    private volatile GovernedIngestionQueue queue;
    private volatile IngestionPerformanceConfig config;
    private volatile DemandProbe demandProbe = DemandProbe.NONE;

    private volatile int lastDrainWorkUnits = 0;
    private volatile boolean lastDrainTimeStopped = false;
    private volatile long lastDrainDurationNanos = 0L;
    private int liveEstimate = 0;
    private volatile int blockedWaiting = 0;
    private DemandProbe drainRequired = DemandProbe.NONE;
    private int roomBatch = 1;
    private boolean roomExhausted = false;

    public IngestionGovernor(WDCCache cache, ChunkIngestionService ingestionService, IngestionPerformanceConfig config) {
        if (cache == null || ingestionService == null || config == null) throw new IllegalArgumentException("args must not be null");
        this.cache = cache;
        this.ingestionService = ingestionService;
        this.config = config;
        this.queue = new GovernedIngestionQueue(config.queueCapacity);
        this.queue.setDeferredMax(config.deferredMax);
    }

    public void onChunkLoaded(WorldKey world, ChunkKey chunk, MinecraftChunkView view, GovernorPriority priority) {
        if (world == null || chunk == null || view == null || priority == null) throw new IllegalArgumentException("args must not be null");
        GovernedIngestionQueue q = this.queue;
        if (classify(world, chunk, q) == IngestionWorkClass.LIVE) {
            WDCMetrics.increment(WDCMetrics.Counter.GOVERNOR_LIVE_SKIPPED);
        } else {
            q.offer(world, chunk, view, priority);
        }
    }

    public void onChunkRefresh(WorldKey world, ChunkKey chunk, MinecraftChunkView view, GovernorPriority priority) {
        if (world == null || chunk == null || view == null || priority == null) throw new IllegalArgumentException("args must not be null");
        queue.offer(world, chunk, view, priority, true);
    }

    IngestionWorkClass classify(WorldKey world, ChunkKey chunk) {
        return classify(world, chunk, this.queue);
    }

    private IngestionWorkClass classify(WorldKey world, ChunkKey chunk, GovernedIngestionQueue q) {
        if (q.contains(world, chunk)) return IngestionWorkClass.IN_FLIGHT;
        DataResult<WDCChunk> current = cache.query(world, chunk.chunkX(), chunk.chunkZ());
        return switch (current.status()) {
            case LIVE -> IngestionWorkClass.LIVE;
            case STALE -> IngestionWorkClass.STALE;
            case UNAVAILABLE -> IngestionWorkClass.UNAVAILABLE;
        };
    }

    public void recordPositionSample(double x, double y, double z, long nowNanos) { movementTracker.sample(x, y, z, nowNanos); }

    public double currentBlocksPerSecond() { return movementTracker.blocksPerSecond(); }

    public void beginTick(long nowNanos, long targetTickNanos) {
        cpuMeter.beginTick(nowNanos);
        tickBudget.beginTick(nowNanos, targetTickNanos);
        GovernedIngestionQueue q = this.queue;
        if (!lastDrainTimeStopped) q.advanceTick();
        q.expireOlderThan(config.timeoutTicks);
        WDCOps.tick();
    }

    public void openDrainWindow() {
        tickBudget.openDrainWindow(System.nanoTime());
    }

    public void applyBudget(BudgetConfig budget) {
        if (budget == null) throw new IllegalArgumentException("budget must not be null");
        budget.validate();
        tickBudget.setPolicy(BudgetPolicy.from(budget));
    }

    public CoreCpuMeter cpuMeter() { return cpuMeter; }

    public CoreMemoryMeter memoryMeter() { return memoryMeter; }

    public void recordCoreNanos(long nanos) {
        cpuMeter.add(nanos);
    }

    public void sampleMemoryIfDue() {
        if (!memoryDemanded()) return;
        long start = System.nanoTime();
        if (memoryMeter.sampleIfDue(cache)) cpuMeter.add(System.nanoTime() - start);
    }

    public CoreMemoryMeter.Snapshot memorySnapshot() {
        memoryDemandTicks = MEMORY_DEMAND_TICKS;
        return memoryMeter.snapshot();
    }

    private boolean memoryDemanded() {
        int left = memoryDemandTicks;
        if (left <= 0) {
            memoryActive = false;
            return memoryMeter.isScanning();
        }
        if (!memoryActive) {
            memoryActive = true;
            memoryMeter.markDue();
        }
        memoryDemandTicks = left - 1;
        return true;
    }

    public TickBudget tickBudget() { return tickBudget; }

    public int budgetPercent() { return tickBudget.percent(); }

    public void setDemandProbe(DemandProbe probe) {
        this.demandProbe = probe == null ? DemandProbe.NONE : probe;
    }

    public DemandProbe demandProbe() { return demandProbe; }

    public int drain() {
        return drainShare(100);
    }

    public int drainShare(int percent) {
        long start = System.nanoTime();
        long allotted = tickBudget.leftoverNanos(start);
        long limit = percent >= 100 ? allotted : TickBudget.share(allotted, Math.max(1, percent));
        DrainReport report = drainWithin(Integer.MAX_VALUE, start, limit);
        long spent = System.nanoTime() - start;
        tickBudget.charge(spent);
        lastDrainWorkUnits = report.processed();
        if (report.processed() > 0) WDCOps.record(WDCOps.Kind.GOVERNOR);
        lastDrainTimeStopped = report.budgetExhausted();
        lastDrainDurationNanos = spent;
        WDCMetrics.record(WDCMetrics.Timer.GOVERNOR_DRAIN, spent);
        return report.processed();
    }

    public int runPhase(WDCMetrics.Timer timer, LongToIntFunction work) {
        if (work == null) throw new IllegalArgumentException("work must not be null");
        long start = System.nanoTime();
        long allotted = tickBudget.leftoverNanos(start);
        if (allotted <= 0L) {
            WDCMetrics.increment(WDCMetrics.Counter.BUDGET_PHASE_SKIPPED);
            return 0;
        }
        int done = work.applyAsInt(allotted);
        long spent = System.nanoTime() - start;
        tickBudget.charge(spent);
        WDCMetrics.record(timer, spent);
        if (spent > allotted) WDCMetrics.increment(WDCMetrics.Counter.BUDGET_PHASE_OVERRUN);
        return done;
    }

    public net.wdc.core.world.ingestion.ChunkIngestionService ingestionService() {
        return ingestionService;
    }

    public boolean advanceMemoryMeter(long budgetNanos) {
        if (!memoryDemanded()) return false;
        return memoryMeter.advanceIfDue(cache, budgetNanos);
    }

    private void admit(WorldKey world, GovernedIngestionQueue.Entry entry) {
        ingestionService.ingest(world, entry.chunkView);
        WDCMetrics.increment(WDCMetrics.Counter.GOVERNOR_COMPLETED);
    }

    public DrainReport drainWaiting(int maxItems) {
        if (maxItems < 1) throw new IllegalArgumentException("maxItems must be >= 1");
        if (!tickBudget.isDrainWindowOpen()) {
            int depth = queue.size();
            return new DrainReport(0, depth, 0, depth > 0, depth == 0);
        }
        if (!tickBudget.isTickThread()) throw new IllegalStateException("drainWaiting must be called on the game thread");
        long start = System.nanoTime();
        DrainReport report = drainWithin(maxItems, start, tickBudget.leftoverNanos(start));
        tickBudget.charge(System.nanoTime() - start);
        return report;
    }

    public int runWithBudget(LongToIntFunction work) {
        if (work == null) throw new IllegalArgumentException("work must not be null");
        long start = System.nanoTime();
        long budget = Math.max(0L, tickBudget.leftoverNanos(start));
        int done = work.applyAsInt(budget);
        tickBudget.charge(System.nanoTime() - start);
        return done;
    }

    public int runInSlice(LongToIntFunction work) {
        if (work == null) throw new IllegalArgumentException("work must not be null");
        long start = System.nanoTime();
        long budget = tickBudget.leftoverNanos(start);
        if (budget <= 0L) return 0;
        int done = work.applyAsInt(budget);
        tickBudget.charge(System.nanoTime() - start);
        return done;
    }

    private DrainReport drainWithin(int itemHint, long start, long budget) {
        IngestionPerformanceConfig cfg = this.config;
        GovernedIngestionQueue q = this.queue;
        if (q.size() == 0) {
            blockedWaiting = 0;
            return new DrainReport(0, 0, 0, false, true);
        }

        liveEstimate = cache.totalLiveCount();
        drainRequired = demandProbe.snapshot();
        roomBatch = 1;
        roomExhausted = false;

        int processed = 0;
        boolean exhausted = false;
        List<GovernedIngestionQueue.Entry> waiting = null;

        while (processed < itemHint) {
            if (System.nanoTime() - start >= budget) { exhausted = true; break; }
            GovernedIngestionQueue.Entry entry = q.poll();
            if (entry == null) break;
            if (processOne(entry, cfg, q.size())) {
                if (waiting == null) waiting = new ArrayList<>();
                waiting.add(entry);
                continue;
            }
            processed++;
        }

        int blocked = 0;
        blockedWaiting = waiting == null ? 0 : waiting.size();
        if (waiting != null) {
            blocked = waiting.size();
            for (GovernedIngestionQueue.Entry entry : waiting) q.requeue(entry);
            WDCMetrics.add(WDCMetrics.Counter.GOVERNOR_LIVE_WAITING, blocked);
        }

        int remaining = q.size();
        boolean timeStopped = exhausted && remaining > blocked;
        if (timeStopped) {
            WDCMetrics.increment(WDCMetrics.Counter.GOVERNOR_BUDGET_EXHAUSTED);
            long newlyDeferred = q.markDeferredAndCount();
            if (newlyDeferred > 0) WDCMetrics.add(WDCMetrics.Counter.GOVERNOR_DEFERRED, newlyDeferred);
        }
        return new DrainReport(processed, remaining, blocked, timeStopped, remaining == 0);
    }

    public void maintain() {
        long start = System.nanoTime();
        runMaintenance(config);
        WDCMetrics.record(WDCMetrics.Timer.PHASE_MAINTENANCE, System.nanoTime() - start);
    }

    public int reviveStale(net.wdc.core.world.ingestion.LoadedChunkProbe probe, net.wdc.core.world.ingestion.ChunkViewSource views,
                           int maxChecks) {
        if (probe == null || views == null) return 0;
        return cache.reviveStale(maxChecks, probe::isLoaded, (world, chunk) -> offerFromMinecraft(world, chunk, views));
    }

    public boolean offerFromMinecraft(WorldKey world, ChunkKey chunk, net.wdc.core.world.ingestion.ChunkViewSource views) {
        if (world == null || chunk == null || views == null) throw new IllegalArgumentException("args must not be null");
        GovernedIngestionQueue q = this.queue;
        if (q.contains(world, chunk)) return false;
        if (cache.totalLiveCount() >= config.liveLimit) return false;
        net.wdc.core.world.ingestion.MinecraftChunkView view = views.viewOf(world, chunk);
        if (view == null) return false;
        if (classify(world, chunk, q) == IngestionWorkClass.LIVE) return false;
        if (!q.offer(world, chunk, view, GovernorPriority.DISTANCE)) return false;
        WDCMetrics.increment(WDCMetrics.Counter.RESYNC_OFFERED);
        return true;
    }

    public int reconcileLive(net.wdc.core.world.ingestion.LoadedChunkProbe probe, int maxChecks,
                             java.util.function.BiConsumer<WorldKey, ChunkKey> onLost) {
        if (probe == null) return 0;
        return cache.reconcileLive(maxChecks, probe::isLoaded, onLost);
    }

    private void runMaintenance(IngestionPerformanceConfig cfg) {
        cache.trimStale();
        DemandProbe required = demandProbe.snapshot();
        int toFree = Math.max(cache.totalLiveCount() - cfg.liveLimit, blockedWaiting);
        if (toFree > 0) {
            cache.releaseIdleLive(toFree, cache.config().evictionBatch(), cfg.liveReleaseGraceNanos(), required::isNeeded);
        }
    }

    private boolean makeRoom(int queueSizeHint) {
        if (roomExhausted) return false;
        int freed = cache.releaseIdleLive(roomBatch, roomBatch, 0L, drainRequired::isNeeded);
        if (freed <= 0) {
            roomExhausted = true;
            return false;
        }
        liveEstimate = Math.max(0, liveEstimate - freed);
        roomBatch = Math.min(roomBatch << 1, Math.max(1, queueSizeHint));
        WDCMetrics.add(WDCMetrics.Counter.GOVERNOR_LIVE_MADE_ROOM, freed);
        return true;
    }

    private boolean processOne(GovernedIngestionQueue.Entry entry, IngestionPerformanceConfig cfg, int queueSizeHint) {
        WorldKey world = entry.key.world();
        ChunkKey chunk = entry.key.chunk();
        if (!cache.isCurrentSession(world)) return false;

        boolean alreadyLive;
        if (!entry.force) {
            DataResult<WDCChunk> current = cache.query(world, chunk.chunkX(), chunk.chunkZ());
            if (current.isLive()) {
                WDCMetrics.increment(WDCMetrics.Counter.GOVERNOR_LIVE_SKIPPED);
                return false;
            }
            if (current.isStale()) {
                WDCMetrics.increment(WDCMetrics.Counter.GOVERNOR_STALE_REVALIDATED);
            }
            alreadyLive = false;
        } else {
            alreadyLive = cache.isLive(world, chunk.chunkX(), chunk.chunkZ());
        }

        if (!alreadyLive) {
            boolean needed = entry.force || demandProbe.isNeeded(world, chunk);
            LiveLimit.Decision decision = LiveLimit.decide(liveEstimate, needed, cfg);
            while (decision == LiveLimit.Decision.WAIT && makeRoom(queueSizeHint)) {
                decision = LiveLimit.decide(liveEstimate, needed, cfg);
            }
            switch (decision) {
                case SKIP -> {
                    WDCMetrics.increment(WDCMetrics.Counter.GOVERNOR_LIVE_LIMIT_SKIPPED);
                    return false;
                }
                case WAIT -> {
                    return true;
                }
                case ADMIT -> { }
            }
        }

        admit(world, entry);
        if (!alreadyLive) liveEstimate++;
        return false;
    }

    public void applyConfig(IngestionPerformanceConfig newConfig) {
        if (newConfig == null) throw new IllegalArgumentException("config must not be null");
        newConfig.validate();
        this.config = newConfig;
        GovernedIngestionQueue q = this.queue;
        if (newConfig.queueCapacity != q.capacity()) {
            q.resize(newConfig.queueCapacity);
        }
        q.setDeferredMax(newConfig.deferredMax);
    }

    public IngestionPerformanceConfig config() { return config; }

    public GovernorMetricsSnapshot metricsSnapshot() {
        return new GovernorMetricsSnapshot(
                queue.size(), queue.maxDepthObserved(), lastDrainWorkUnits, lastDrainDurationNanos, tickBudget.snapshot()
        );
    }

    public void invalidateWorld(WorldKey world) {
        if (world == null) return;
        queue.removeAllForWorld(world);
    }

    public void invalidateChunk(WorldKey world, ChunkKey chunk) {
        if (world == null || chunk == null) return;
        queue.remove(world, chunk);
    }

    public void reset() {
        queue.clear();
        movementTracker.reset();
        cpuMeter.reset();
        memoryMeter.reset();
        lastDrainWorkUnits = 0;
        lastDrainDurationNanos = 0L;
        liveEstimate = 0;
        blockedWaiting = 0;
    }

    public int queueDepth() { return queue.size(); }

    public int queueCapacity() { return queue.capacity(); }

    public int deferredInQueue() { return queue.deferredInQueue(); }

    public boolean isQueued(WorldKey world, ChunkKey chunk) {
        return queue.contains(world, chunk);
    }
}
