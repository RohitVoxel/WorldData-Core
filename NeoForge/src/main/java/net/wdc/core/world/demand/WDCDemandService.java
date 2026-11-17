package net.wdc.core.world.demand;

import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.debug.WDCOps;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.DataFreshness;
import net.wdc.core.world.cache.DataResult;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.spill.DelayedSpillPolicy;
import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.ChunkKey;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;
import net.wdc.core.world.storage.GlobalBlockStateRegistryProvider;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class WDCDemandService {
    public static final int DEFAULT_MAX_DERIVED_LAYER_ENTRIES = 1024;
    private static final Object HELD_INTEREST = new Object();

    private final WDCCache cache;
    private final AdaptivePriorityScheduler<ChunkTask> scheduler = new AdaptivePriorityScheduler<>();
    private final Map<WorldKey, Map<ChunkKey, ChunkRefCount>> refCounts = new ConcurrentHashMap<>();
    private final Map<Object, QueryPriority> priorityByQuery = new ConcurrentHashMap<>();
    private final Map<DataLayer, LayerBuilder<?>> layerBuilders = new ConcurrentHashMap<>();

    private final AtomicLong generation = new AtomicLong();

    @FunctionalInterface
    public interface ChunkRefresher {
        boolean refresh(WorldKey world, ChunkKey chunk);
    }

    private volatile DelayedSpillPolicy spillPolicy;
    private volatile ChunkRefresher refresher;

    public WDCDemandService(WDCCache cache) {
        this(cache, GlobalBlockStateRegistryProvider.getOrCreate());
    }

    public WDCDemandService(WDCCache cache, GlobalBlockStateRegistry registry) {
        if (cache == null) throw new IllegalArgumentException("cache must not be null");
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        this.cache = cache;
        registerLayerBuilder(DataLayer.BLOCK_STATES, chunk -> chunk);
        registerLayerBuilder(DataLayer.HEIGHTMAP, chunk -> ChunkHeightmap.build(chunk, registry));
        registerLayerBuilder(DataLayer.COLUMN, chunk -> ChunkColumnCache.build(chunk, registry));
        registerLayerBuilder(DataLayer.BLOCK_INDEX, ChunkBlockIndex::build);
        registerLayerBuilder(DataLayer.OCCUPANCY, chunk -> ChunkOccupancy.build(chunk, registry));
    }

    public WDCCache cache() { return cache; }

    public long currentGeneration() { return generation.get(); }

    public void setSpillPolicy(DelayedSpillPolicy spillPolicy) { this.spillPolicy = spillPolicy; }

    public void setRefresher(ChunkRefresher refresher) { this.refresher = refresher; }

    public <T> void registerLayerBuilder(DataLayer layer, LayerBuilder<T> builder) {
        if (layer == null || builder == null) throw new IllegalArgumentException("layer/builder must not be null");
        layerBuilders.put(layer, builder);
    }

    public QueryHandle createQuery(QueryScope scope, int maxActiveWindow) {
        if (scope == null) throw new IllegalArgumentException("scope must not be null");
        return new QueryHandle(this, cache.currentWorldKey(scope.dimensionId()), scope, maxActiveWindow);
    }

    public DemandResult query(WorldKey world, ChunkKey chunk, Set<DataLayer> layers) {
        if (world == null || chunk == null || layers == null) throw new IllegalArgumentException("args must not be null");
        Set<DataLayer> expanded = expandDependencies(layers);
        DataResult<WDCChunk> cached = cache.query(world, chunk.chunkX(), chunk.chunkZ());
        boolean acquiring = scheduler.isQueued(new ChunkTask(world, chunk));

        Map<DataLayer, LayerResult<?>> results = new LinkedHashMap<>();
        for (DataLayer layer : expanded) {
            results.put(layer, resolveLayer(world, chunk, layer, cached, acquiring));
        }
        return new DemandResult(chunk, results);
    }

    public DemandResult requestCurrent(WorldKey world, ChunkDemand demand) {
        if (world == null || demand == null) throw new IllegalArgumentException("args must not be null");
        ChunkKey chunk = demand.chunk();
        Set<DataLayer> expanded = expandDependencies(demand.layers());
        DataResult<WDCChunk> cached = cache.query(world, chunk.chunkX(), chunk.chunkZ());
        boolean needsWork = cached.status() != DataFreshness.LIVE;
        if (needsWork && cache.isCurrentSession(world)) {
            scheduler.enqueue(new ChunkTask(world, chunk), demand.priority());
        }

        boolean acquiring = scheduler.isQueued(new ChunkTask(world, chunk));
        Map<DataLayer, LayerResult<?>> results = new LinkedHashMap<>();
        for (DataLayer layer : expanded) {
            results.put(layer, resolveLayer(world, chunk, layer, cached, acquiring));
        }
        return new DemandResult(chunk, results);
    }

    public int rebuildStaleLayers(WorldKey world, ChunkKey chunk) {
        WDCCache.EntryView view = cache.inspect(world, chunk.chunkX(), chunk.chunkZ());
        WDCChunk source = view == null ? null : view.chunk();
        if (source == null) return 0;
        long currentGeneration = generation.get();
        int rebuilt = 0;
        for (Map.Entry<DataLayer, LayerBuilder<?>> entry : layerBuilders.entrySet()) {
            int slot = entry.getKey().slotIndex();
            if (!source.derivedSlotBuilt(slot) || source.derivedSlotGet(slot, currentGeneration) != null) continue;
            synchronized (source) {
                if (source.derivedSlotGet(slot, currentGeneration) != null) continue;
                buildLayer(source, entry.getKey(), entry.getValue(), currentGeneration);
            }
            rebuilt++;
        }
        return rebuilt;
    }

    public Object layerOf(WDCChunk source, DataLayer layer) {
        if (source == null || layer == null) throw new IllegalArgumentException("source/layer must not be null");
        if (DataLayer.BLOCK_ENTITIES.equals(layer)) return null;
        if (layer.equals(DataLayer.BLOCK_STATES)) return source;
        return derivedLayerValue(null, null, layer, source);
    }

    private LayerResult<?> resolveLayer(WorldKey world, ChunkKey chunk, DataLayer layer, DataResult<WDCChunk> cached, boolean acquiring) {
        if (DataLayer.BLOCK_ENTITIES.equals(layer)) {
            return new LayerResult<>(DemandFreshness.UNAVAILABLE, null);
        }
        DemandFreshness freshness = switch (cached.status()) {
            case LIVE -> DemandFreshness.LIVE;
            case STALE -> acquiring ? DemandFreshness.ACQUIRING : DemandFreshness.STALE;
            case UNAVAILABLE -> acquiring ? DemandFreshness.ACQUIRING : DemandFreshness.UNAVAILABLE;
        };
        if (freshness == DemandFreshness.UNAVAILABLE || freshness == DemandFreshness.ACQUIRING || cached.value() == null) {
            return new LayerResult<>(freshness, null);
        }
        if (layer.equals(DataLayer.BLOCK_STATES)) {
            return new LayerResult<>(freshness, cached.value());
        }
        Object value = derivedLayerValue(world, chunk, layer, cached.value());
        return new LayerResult<>(freshness, value);
    }

    private Object derivedLayerValue(WorldKey world, ChunkKey chunk, DataLayer layer, WDCChunk source) {
        LayerBuilder<?> builder = layerBuilders.get(layer);
        if (builder == null) return source;

        long currentGeneration = generation.get();
        Object existing = source.derivedSlotGet(layer.slotIndex(), currentGeneration);
        if (existing != null) {
            WDCMetrics.increment(WDCMetrics.Counter.DERIVED_LAYER_REUSED);
            return existing;
        }

        synchronized (source) {
            existing = source.derivedSlotGet(layer.slotIndex(), currentGeneration);
            if (existing != null) {
                WDCMetrics.increment(WDCMetrics.Counter.DERIVED_LAYER_JOINED);
                return existing;
            }
            return buildLayer(source, layer, builder, currentGeneration);
        }
    }

    private static Object buildLayer(WDCChunk source, DataLayer layer, LayerBuilder<?> builder, long currentGeneration) {
        long builtAtVersion = source.version();
        Object built = builder.build(source);
        source.derivedSlotPut(layer.slotIndex(), currentGeneration, built, builtAtVersion);
        WDCMetrics.increment(WDCMetrics.Counter.DERIVED_LAYER_BUILT);
        return built;
    }

    public int processWithin(long budgetNanos) {
        if (budgetNanos <= 0L) return 0;
        long start = WDCMetrics.startNanos();
        int processed = scheduler.drainWithin(budgetNanos, this::processOne);
        WDCMetrics.record(WDCMetrics.Timer.DEMAND_PROCESSING, System.nanoTime() - start);
        return processed;
    }

    private void processOne(ChunkTask task) {
        if (!cache.isCurrentSession(task.world())) return;
        WDCOps.record(WDCOps.Kind.DEMAND);
        DataResult<WDCChunk> current = cache.query(task.world(), task.chunk().chunkX(), task.chunk().chunkZ());
        if (current.isLive()) return;
        int x = task.chunk().chunkX();
        int z = task.chunk().chunkZ();
        if (current.isUnavailable()) {
            DelayedSpillPolicy policy = this.spillPolicy;
            boolean restoring = policy != null && policy.restore(task.world(), x, z);
            if (!restoring) {
                ChunkRefresher fromMinecraft = this.refresher;
                if (fromMinecraft != null) fromMinecraft.refresh(task.world(), task.chunk());
            }
            return;
        }
        if (current.value() == null) return;
        ChunkRefresher active = this.refresher;
        if (active != null) active.refresh(task.world(), task.chunk());
    }

    public int interestedQueryCount(WorldKey world, ChunkKey chunk) {
        Map<ChunkKey, ChunkRefCount> dim = refCounts.get(world);
        if (dim == null) return 0;
        ChunkRefCount ref = dim.get(chunk);
        return ref == null ? 0 : ref.interestedQueryCount();
    }

    public int trackedQueryCount() { return priorityByQuery.size(); }

    public void reset() {
        scheduler.clear();
        refCounts.clear();
        priorityByQuery.clear();
        generation.incrementAndGet();
    }

    void addDemand(Object queryId, WorldKey world, ChunkKey chunk, Set<DataLayer> layers, QueryPriority priority) {
        priorityByQuery.put(queryId, priority);
        addRef(queryId, world, chunk, priority);
    }

    void releaseDemand(Object queryId, WorldKey world, ChunkKey chunk) {
        dropRef(queryId, world, chunk);
    }

    public void holdInterest(WorldKey world, ChunkKey chunk, QueryPriority priority) {
        if (world == null || chunk == null || priority == null) throw new IllegalArgumentException("args must not be null");
        addRef(HELD_INTEREST, world, chunk, priority);
    }

    public void releaseHeldInterest(WorldKey world, ChunkKey chunk) {
        if (world == null || chunk == null) return;
        dropRef(HELD_INTEREST, world, chunk);
    }

    private void addRef(Object holder, WorldKey world, ChunkKey chunk, QueryPriority priority) {
        Map<ChunkKey, ChunkRefCount> dim = refCounts.computeIfAbsent(world, w -> new ConcurrentHashMap<>());
        dim.compute(chunk, (k, ref) -> {
            ChunkRefCount next = (ref == null) ? new ChunkRefCount() : ref;
            next.addQuery(holder, priority);
            return next;
        });
    }

    private void dropRef(Object holder, WorldKey world, ChunkKey chunk) {
        Map<ChunkKey, ChunkRefCount> dim = refCounts.get(world);
        if (dim == null) return;
        dim.compute(chunk, (k, ref) -> (ref == null || ref.removeQuery(holder)) ? null : ref);
        if (dim.isEmpty()) refCounts.remove(world, dim);
    }

    void forgetQuery(Object queryId) { priorityByQuery.remove(queryId); }

    void scheduleAcquire(WorldKey world, ChunkKey chunk, QueryPriority priority) {
        if (!cache.isCurrentSession(world)) return;
        scheduler.enqueue(new ChunkTask(world, chunk), priority);
    }

    static Set<DataLayer> expandDependencies(Set<DataLayer> requested) {
        Set<DataLayer> out = new HashSet<>();
        ArrayDeque<DataLayer> pending = new ArrayDeque<>(requested);
        while (!pending.isEmpty()) {
            DataLayer layer = pending.pop();
            if (out.add(layer)) pending.addAll(layer.dependencies());
        }
        return out;
    }

    public boolean isAcquiring(WorldKey world, ChunkKey chunk) {
        return scheduler.isQueued(new ChunkTask(world, chunk));
    }

    record ChunkTask(WorldKey world, ChunkKey chunk) {}

    static final class ChunkRefCount {
        private final Map<Object, QueryPriority> priorities = new HashMap<>();

        synchronized void addQuery(Object queryId, QueryPriority priority) {
            priorities.put(queryId, priority);
        }

        synchronized boolean removeQuery(Object queryId) {
            priorities.remove(queryId);
            return priorities.isEmpty();
        }

        synchronized int interestedQueryCount() { return priorities.size(); }
    }
}
