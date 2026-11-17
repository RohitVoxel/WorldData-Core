package net.wdc.core.world.cache;

import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.ChunkKey;
import net.wdc.core.world.work.CoreThreads;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class WDCCache {
    private final ChunkFingerprintSource fingerprintSource;
    private final WDCCacheConfig config;

    private final Map<String, DimensionEntries> byDimension = new ConcurrentHashMap<>();

    private final AtomicLong accessCounter = new AtomicLong();
    private volatile LongSupplier clock = System::nanoTime;
    private final AtomicLong sessionCounter = new AtomicLong();
    private volatile long currentSessionId = 0L;

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final ConcurrentHashMap<String, WorldKey> worldKeys = new ConcurrentHashMap<>();
    private final AtomicLong rebuilds = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    @FunctionalInterface
    public interface EvictionListener {
        void onEvicted(String dimensionId, ChunkKey chunkKey);
    }

    private volatile EvictionListener evictionListener;

    public interface FreshnessListener {
        void onFreshnessChanged(String dimensionId, ChunkKey chunkKey, DataFreshness before, DataFreshness after);

        default void onCleared() {}
    }

    private volatile FreshnessListener freshnessListener;

    public interface PublishListener {
        void onPublished(String dimensionId, int chunkX, int chunkZ, WDCChunk chunk);

        default void onCleared() {}
    }

    private volatile PublishListener publishListener;

    public void setPublishListener(PublishListener listener) {
        this.publishListener = listener;
    }

    private void firePublished(String dimensionId, int chunkX, int chunkZ, WDCChunk chunk) {
        PublishListener listener = this.publishListener;
        if (listener == null || chunk == null) return;
        try {
            listener.onPublished(dimensionId, chunkX, chunkZ, chunk);
        } catch (RuntimeException ignored) {
            WDCMetrics.increment(WDCMetrics.Counter.CHANGE_LISTENER_FAILURES);
        }
    }

    @FunctionalInterface
    public interface ReadHook {
        void beforeRead(WorldKey world, int chunkX, int chunkZ);
    }

    private volatile ReadHook readHook;

    public void setReadHook(ReadHook hook) {
        this.readHook = hook;
    }

    public void setEvictionListener(EvictionListener listener) {
        this.evictionListener = listener;
    }

    public void setFreshnessListener(FreshnessListener listener) {
        this.freshnessListener = listener;
    }

    private void fireFreshness(String dimensionId, ChunkKey key, DataFreshness before, DataFreshness after) {
        FreshnessListener listener = this.freshnessListener;
        if (listener == null || before == after) return;
        try {
            listener.onFreshnessChanged(dimensionId, key, before, after);
        } catch (RuntimeException ignored) {
            WDCMetrics.increment(WDCMetrics.Counter.CHANGE_LISTENER_FAILURES);
        }
    }

    private void fireCleared() {
        PublishListener publish = this.publishListener;
        if (publish != null) {
            try {
                publish.onCleared();
            } catch (RuntimeException ignored) {
                WDCMetrics.increment(WDCMetrics.Counter.CHANGE_LISTENER_FAILURES);
            }
        }
        FreshnessListener listener = this.freshnessListener;
        if (listener == null) return;
        try {
            listener.onCleared();
        } catch (RuntimeException ignored) {
            WDCMetrics.increment(WDCMetrics.Counter.CHANGE_LISTENER_FAILURES);
        }
    }

    public void setClock(LongSupplier clock) {
        this.clock = clock == null ? System::nanoTime : clock;
    }

    public WDCCache(ChunkFingerprintSource fingerprintSource) {
        this(fingerprintSource, new WDCCacheConfig());
    }

    public WDCCache(ChunkFingerprintSource fingerprintSource, WDCCacheConfig config) {
        if (fingerprintSource == null) throw new IllegalArgumentException("fingerprintSource must not be null");
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.fingerprintSource = fingerprintSource;
        this.config = config;
    }

    public WDCCacheConfig config() {
        return config;
    }

    public WorldKey currentWorldKey(String dimensionId) {
        long session = currentSessionId;
        if (dimensionId == null) return new WorldKey(null, session);
        WorldKey cached = worldKeys.get(dimensionId);
        if (cached != null && cached.sessionId() == session) return cached;
        WorldKey created = new WorldKey(dimensionId, session);
        if (worldKeys.size() < 256) worldKeys.put(dimensionId, created);
        return created;
    }

    public void invalidateSession() {
        currentSessionId = sessionCounter.incrementAndGet();
        fireCleared();
    }

    public DataResult<WDCChunk> query(WorldKey world, int chunkX, int chunkZ) {
        if (!isCurrentSession(world)) return DataResult.unavailable();
        ReadHook hook = readHook;
        if (hook != null) hook.beforeRead(world, chunkX, chunkZ);
        ChunkCacheEntry entry = getEntry(world.dimensionId(), chunkX, chunkZ);
        if (entry == null) {
            misses.increment();
            return DataResult.unavailable();
        }
        touch(entry);
        return switch (entry.freshness()) {
            case LIVE -> {
                hits.increment();
                WDCMetrics.increment(WDCMetrics.Counter.CACHE_LIVE_HITS);
                yield entry.resultFor(DataFreshness.LIVE, entry.data(), currentFingerprint(world.dimensionId(), chunkX, chunkZ, entry));
            }
            case STALE -> {
                hits.increment();
                WDCMetrics.increment(WDCMetrics.Counter.CACHE_STALE_VALIDATIONS);
                yield entry.resultFor(DataFreshness.STALE, entry.data(), currentFingerprint(world.dimensionId(), chunkX, chunkZ, entry));
            }
            case UNAVAILABLE -> {
                misses.increment();
                yield DataResult.unavailable();
            }
        };
    }

    public DataResult<WDCChunk> onChunkObserved(WorldKey world, int chunkX, int chunkZ, Supplier<WDCChunk> currentChunkSupplier) {
        CoreThreads.requireNotWorker("cache publish");
        if (!isCurrentSession(world)) {
            throw new IllegalStateException("onChunkObserved called with a WorldKey from a stale/previous session: " + world);
        }
        long publishStart = WDCMetrics.startNanos();
        try {
            return doOnChunkObserved(world, chunkX, chunkZ, currentChunkSupplier);
        } finally {
            WDCMetrics.record(WDCMetrics.Timer.CACHE_PUBLICATION, System.nanoTime() - publishStart);
        }
    }

    private DataResult<WDCChunk> doOnChunkObserved(WorldKey world, int chunkX, int chunkZ, Supplier<WDCChunk> currentChunkSupplier) {
        String dimensionId = world.dimensionId();
        DimensionEntries dim = dimensionEntries(dimensionId);
        ChunkKey chunkKey = new ChunkKey(chunkX, chunkZ);
        ChunkCacheEntry entry = dim.get(chunkKey);

        if (entry == null) {
            WDCChunk fresh = currentChunkSupplier.get();
            ChunkFingerprint fingerprint = safeFingerprint(dimensionId, chunkX, chunkZ, fresh);
            rebuilds.incrementAndGet();
            WDCMetrics.increment(WDCMetrics.Counter.CACHE_REBUILDS);
            ChunkCacheEntry created = new ChunkCacheEntry(
                    new CachedChunkKey(world, chunkKey), fresh, fingerprint, DataFreshness.LIVE, nextAccessTick(), clock.getAsLong());
            dim.put(chunkKey, created);
            firePublished(dimensionId, chunkX, chunkZ, fresh);
            fireFreshness(dimensionId, chunkKey, DataFreshness.UNAVAILABLE, DataFreshness.LIVE);
            return DataResult.live(fresh, fingerprint);
        }

        boolean wasStale = entry.freshness() == DataFreshness.STALE;
        boolean hadPending = entry.fingerprintDirty();
        ChunkFingerprint previousFingerprint = hadPending ? null : entry.lastValidatedFingerprint();
        WDCChunk current = currentChunkSupplier.get();
        ChunkFingerprint currentFingerprint = safeFingerprint(dimensionId, chunkX, chunkZ, current);

        boolean unchanged = previousFingerprint != null
                && previousFingerprint.isComplete()
                && currentFingerprint.isComplete()
                && previousFingerprint.matches(currentFingerprint);

        if (wasStale) WDCMetrics.increment(WDCMetrics.Counter.CACHE_STALE_VALIDATIONS);

        DataFreshness before = entry.freshness();
        if (unchanged) {
            dim.setFreshness(entry, DataFreshness.LIVE);
            touch(entry);
            hits.increment();
            fireFreshness(dimensionId, chunkKey, before, DataFreshness.LIVE);
            return DataResult.live(entry.data(), entry.lastValidatedFingerprint());
        }

        rebuilds.incrementAndGet();
        WDCMetrics.increment(WDCMetrics.Counter.CACHE_REBUILDS);
        entry.setData(current);
        firePublished(dimensionId, chunkX, chunkZ, current);
        entry.setLastValidatedFingerprint(currentFingerprint);
        dim.setFreshness(entry, DataFreshness.LIVE);
        touch(entry);
        fireFreshness(dimensionId, chunkKey, before, DataFreshness.LIVE);
        return DataResult.live(current, currentFingerprint);
    }

    public void markUnloaded(WorldKey world, int chunkX, int chunkZ) {
        CoreThreads.requireNotWorker("cache unload");
        if (!isCurrentSession(world)) return;
        DimensionEntries dim = byDimension.get(world.dimensionId());
        if (dim == null) return;
        ChunkCacheEntry entry = dim.get(new ChunkKey(chunkX, chunkZ));
        if (entry == null) return;
        if (entry.freshness() == DataFreshness.UNAVAILABLE) return;
        DataFreshness before = entry.freshness();
        dim.setFreshness(entry, DataFreshness.STALE);
        touch(entry);
        fireFreshness(world.dimensionId(), entry.key().chunk(), before, DataFreshness.STALE);
        enforceStaleLimit(world.dimensionId());
    }

    public boolean onIncrementalMutation(WorldKey world, int chunkX, int chunkZ) {
        CoreThreads.requireNotWorker("cache mutation");
        if (!isCurrentSession(world)) return false;
        ChunkCacheEntry entry = getEntry(world.dimensionId(), chunkX, chunkZ);
        if (entry == null || entry.freshness() != DataFreshness.LIVE) return false;
        entry.markFingerprintDirty();
        touch(entry);
        return true;
    }

    public boolean evictOldestStale(String dimensionId) {
        CoreThreads.requireNotWorker("cache eviction");
        DimensionEntries dim = byDimension.get(dimensionId);
        if (dim == null || dim.staleCount() == 0) return false;
        ChunkCacheEntry oldest = null;
        for (ChunkCacheEntry entry : dim.staleSnapshot()) {
            if (oldest == null || entry.lastAccessTick() < oldest.lastAccessTick()) oldest = entry;
        }
        return oldest != null && removeEntry(dimensionId, oldest);
    }

    public int staleCount(String dimensionId) {
        DimensionEntries dim = byDimension.get(dimensionId);
        return dim == null ? 0 : dim.staleCount();
    }

    public record EntryView(DataFreshness freshness, WDCChunk chunk) {}

    @FunctionalInterface
    public interface EntryVisitor {
        void visit(String dimensionId, ChunkKey key, DataFreshness freshness, WDCChunk chunk);
    }

    public WDCChunk liveChunk(String dimensionId, int chunkX, int chunkZ) {
        ChunkCacheEntry entry = getEntry(dimensionId, chunkX, chunkZ);
        if (entry == null || entry.freshness() != DataFreshness.LIVE) return null;
        if (entry.key().world().sessionId() != currentSessionId) return null;
        return entry.data();
    }

    public EntryView inspect(WorldKey world, int chunkX, int chunkZ) {
        if (!isCurrentSession(world)) return null;
        ChunkCacheEntry entry = getEntry(world.dimensionId(), chunkX, chunkZ);
        if (entry == null || entry.data() == null) return null;
        DataFreshness freshness = entry.freshness();
        if (freshness == DataFreshness.UNAVAILABLE) return null;
        return new EntryView(freshness, entry.data());
    }

    public void forEachEntry(EntryVisitor visitor) {
        for (Map.Entry<String, DimensionEntries> dim : byDimension.entrySet()) {
            for (ChunkCacheEntry entry : dim.getValue().values()) {
                DataFreshness freshness = entry.freshness();
                WDCChunk chunk = entry.data();
                if (chunk == null || freshness == DataFreshness.UNAVAILABLE) continue;
                visitor.visit(dim.getKey(), entry.key().chunk(), freshness, chunk);
            }
        }
    }

    public CacheStats stats() {
        int live = 0;
        int stale = 0;
        for (DimensionEntries dim : byDimension.values()) {
            live += dim.liveCount();
            stale += dim.staleCount();
        }
        return new CacheStats(hits.sum(), misses.sum(), rebuilds.get(), evictions.get(), live, stale);
    }

    public void clearAll() {
        byDimension.clear();
        fireCleared();
    }

    public boolean isCurrentSession(WorldKey world) {
        return world != null && world.sessionId() == currentSessionId;
    }

    private ChunkCacheEntry getEntry(String dimensionId, int chunkX, int chunkZ) {
        DimensionEntries dim = byDimension.get(dimensionId);
        if (dim == null) return null;
        return dim.get(new ChunkKey(chunkX, chunkZ));
    }

    public boolean evictEntry(WorldKey world, int chunkX, int chunkZ) {
        CoreThreads.requireNotWorker("cache eviction");
        if (!isCurrentSession(world)) return false;
        DimensionEntries dim = byDimension.get(world.dimensionId());
        if (dim == null) return false;
        ChunkCacheEntry entry = dim.get(new ChunkKey(chunkX, chunkZ));
        if (entry == null || entry.freshness() != DataFreshness.STALE) return false;
        return removeEntry(world.dimensionId(), entry);
    }

    public int liveCount(String dimensionId) {
        DimensionEntries dim = byDimension.get(dimensionId);
        return dim == null ? 0 : dim.liveCount();
    }

    public long evictionCount() {
        return evictions.get();
    }

    public void injectRestored(WorldKey world, int chunkX, int chunkZ, WDCChunk chunk) {
        CoreThreads.requireNotWorker("cache restore publish");
        if (!isCurrentSession(world) || chunk == null) return;
        ChunkKey key = new ChunkKey(chunkX, chunkZ);
        DimensionEntries dim = dimensionEntries(world.dimensionId());
        if (dim.get(key) != null) return;
        ChunkFingerprint fp = safeFingerprint(world.dimensionId(), chunkX, chunkZ, chunk);
        dim.put(key, new ChunkCacheEntry(new CachedChunkKey(world, key), chunk, fp,
                DataFreshness.STALE, nextAccessTick(), clock.getAsLong()));
        firePublished(world.dimensionId(), chunkX, chunkZ, chunk);
        fireFreshness(world.dimensionId(), key, DataFreshness.UNAVAILABLE, DataFreshness.STALE);
    }

    private DimensionEntries dimensionEntries(String dimensionId) {
        return byDimension.computeIfAbsent(dimensionId, d -> new DimensionEntries());
    }

    private long nextAccessTick() {
        return accessCounter.incrementAndGet();
    }

    private void touch(ChunkCacheEntry entry) {
        entry.touch(nextAccessTick(), clock.getAsLong());
    }

    private ChunkFingerprint currentFingerprint(String dimensionId, int chunkX, int chunkZ, ChunkCacheEntry entry) {
        if (entry.fingerprintDirty()) {
            ChunkFingerprint fresh = safeFingerprint(dimensionId, chunkX, chunkZ, entry.data());
            entry.setLastValidatedFingerprint(fresh);
            return fresh;
        }
        return entry.lastValidatedFingerprint();
    }

    private ChunkFingerprint safeFingerprint(String dimensionId, int chunkX, int chunkZ, WDCChunk chunk) {
        try {
            ChunkFingerprint fp = fingerprintSource.fingerprintOf(dimensionId, chunkX, chunkZ, chunk);
            return fp != null ? fp : ChunkFingerprint.unavailable(dimensionId, chunkX, chunkZ);
        } catch (RuntimeException computationFailed) {
            return ChunkFingerprint.unavailable(dimensionId, chunkX, chunkZ);
        }
    }

    private void enforceStaleLimit(String dimensionId) {
        trimStaleDimension(dimensionId, config.maxStaleChunksPerDimension(), config.evictionBatch());
    }

    private int trimStaleDimension(String dimensionId, int limit, int maxEvict) {
        DimensionEntries dim = byDimension.get(dimensionId);
        if (dim == null || maxEvict <= 0) return 0;
        int floor = Math.max(limit, 0);
        if (dim.staleCount() <= floor) return 0;
        List<EvictionCandidate> stale = EvictionCandidate.oldestFirst(dimensionId, dim.staleSnapshot());
        int excess = stale.size() - floor;
        if (excess <= 0) return 0;
        int target = Math.min(excess, maxEvict);
        int evicted = 0;
        for (int i = 0; i < target; i++) {
            if (removeEntry(dimensionId, stale.get(i).entry())) evicted++;
        }
        return evicted;
    }

    private boolean removeEntry(String dimensionId, ChunkCacheEntry entry) {
        DimensionEntries dim = byDimension.get(dimensionId);
        if (dim == null) return false;
        ChunkKey key = entry.key().chunk();
        if (!dim.remove(key, entry)) return false;
        evictions.incrementAndGet();
        WDCMetrics.increment(WDCMetrics.Counter.CACHE_EVICTIONS);
        EvictionListener listener = this.evictionListener;
        if (listener != null) listener.onEvicted(dimensionId, key);
        fireFreshness(dimensionId, key, entry.freshness(), DataFreshness.UNAVAILABLE);
        return true;
    }

    public int trimStale() {
        CoreThreads.requireNotWorker("cache eviction");
        int evicted = 0;
        int batch = config.evictionBatch();
        int limit = config.maxStaleChunksPerDimension();
        for (String dimensionId : byDimension.keySet()) {
            evicted += trimStaleDimension(dimensionId, limit, batch);
        }
        return evicted;
    }

    public long idleNanos(WorldKey world, int chunkX, int chunkZ) {
        if (!isCurrentSession(world)) return -1L;
        ChunkCacheEntry entry = getEntry(world.dimensionId(), chunkX, chunkZ);
        if (entry == null || entry.freshness() != DataFreshness.LIVE) return -1L;
        return Math.max(0L, clock.getAsLong() - entry.lastAccessNanos());
    }

    public boolean releaseLive(WorldKey world, int chunkX, int chunkZ) {
        CoreThreads.requireNotWorker("cache release");
        if (!isCurrentSession(world)) return false;
        ChunkCacheEntry entry = getEntry(world.dimensionId(), chunkX, chunkZ);
        if (entry == null || entry.freshness() != DataFreshness.LIVE) return false;
        if (!removeEntry(world.dimensionId(), entry)) return false;
        WDCMetrics.increment(WDCMetrics.Counter.CACHE_LIVE_RELEASED);
        return true;
    }

    public int releaseIdleLive(int count, int maxRelease, long minIdleNanos, BiPredicate<WorldKey, ChunkKey> keep) {
        CoreThreads.requireNotWorker("cache release");
        if (count <= 0 || maxRelease <= 0) return 0;
        long nowNanos = clock.getAsLong();
        List<EvictionCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, DimensionEntries> dim : byDimension.entrySet()) {
            if (dim.getValue().liveCount() == 0) continue;
            for (ChunkCacheEntry entry : dim.getValue().values()) {
                if (entry.freshness() != DataFreshness.LIVE) continue;
                if (nowNanos - entry.lastAccessNanos() < minIdleNanos) continue;
                CachedChunkKey key = entry.key();
                if (keep != null && keep.test(key.world(), key.chunk())) continue;
                candidates.add(EvictionCandidate.of(dim.getKey(), entry));
            }
        }
        if (candidates.isEmpty()) return 0;
        candidates.sort(EvictionCandidate.OLDEST_FIRST);
        int target = Math.min(Math.min(count, maxRelease), candidates.size());
        int released = 0;
        for (int i = 0; i < target; i++) {
            EvictionCandidate candidate = candidates.get(i);
            if (removeEntry(candidate.dimensionId(), candidate.entry())) {
                released++;
                WDCMetrics.increment(WDCMetrics.Counter.CACHE_LIVE_RELEASED);
            }
        }
        return released;
    }

    private java.util.Iterator<DimensionEntries> reconcileDimensions;
    private java.util.Iterator<ChunkCacheEntry> reconcileEntries;
    private java.util.Iterator<DimensionEntries> reviveDimensions;
    private java.util.Iterator<ChunkCacheEntry> reviveEntries;

    public int reviveStale(int maxChecks, BiPredicate<WorldKey, ChunkKey> minecraftHolds,
                           java.util.function.BiConsumer<WorldKey, ChunkKey> onRevive) {
        CoreThreads.requireNotWorker("cache revive");
        if (maxChecks <= 0) return 0;
        List<ChunkCacheEntry> held = null;
        int checked = 0;
        while (checked < maxChecks) {
            if (reviveEntries == null || !reviveEntries.hasNext()) {
                if (reviveDimensions == null || !reviveDimensions.hasNext()) {
                    boolean wrapped = reviveDimensions != null;
                    reviveDimensions = byDimension.values().iterator();
                    reviveEntries = null;
                    if (wrapped || !reviveDimensions.hasNext()) break;
                }
                reviveEntries = reviveDimensions.next().values().iterator();
                continue;
            }
            ChunkCacheEntry entry = reviveEntries.next();
            if (entry.freshness() != DataFreshness.STALE) continue;
            checked++;
            CachedChunkKey key = entry.key();
            if (!isCurrentSession(key.world())) continue;
            if (minecraftHolds.test(key.world(), key.chunk())) {
                if (held == null) held = new ArrayList<>();
                held.add(entry);
            }
        }
        if (held == null) return 0;
        for (ChunkCacheEntry entry : held) {
            CachedChunkKey key = entry.key();
            onRevive.accept(key.world(), key.chunk());
            WDCMetrics.increment(WDCMetrics.Counter.STALE_REVIVED);
        }
        return held.size();
    }

    public int reconcileLive(int maxChecks, BiPredicate<WorldKey, ChunkKey> minecraftHolds,
                             java.util.function.BiConsumer<WorldKey, ChunkKey> onLost) {
        CoreThreads.requireNotWorker("cache reconcile");
        if (maxChecks <= 0) return 0;
        List<ChunkCacheEntry> lost = null;
        int checked = 0;
        while (checked < maxChecks) {
            if (reconcileEntries == null || !reconcileEntries.hasNext()) {
                if (reconcileDimensions == null || !reconcileDimensions.hasNext()) {
                    boolean wrapped = reconcileDimensions != null;
                    reconcileDimensions = byDimension.values().iterator();
                    reconcileEntries = null;
                    if (wrapped || !reconcileDimensions.hasNext()) break;
                }
                reconcileEntries = reconcileDimensions.next().values().iterator();
                continue;
            }
            ChunkCacheEntry entry = reconcileEntries.next();
            if (entry.freshness() != DataFreshness.LIVE) continue;
            checked++;
            CachedChunkKey key = entry.key();
            if (!isCurrentSession(key.world())) continue;
            if (!minecraftHolds.test(key.world(), key.chunk())) {
                if (lost == null) lost = new ArrayList<>();
                lost.add(entry);
            }
        }
        if (lost == null) return 0;
        for (ChunkCacheEntry entry : lost) {
            CachedChunkKey key = entry.key();
            onLost.accept(key.world(), key.chunk());
            if (entry.freshness() == DataFreshness.LIVE) markUnloaded(key.world(), key.chunk().chunkX(), key.chunk().chunkZ());
            WDCMetrics.increment(WDCMetrics.Counter.LIVE_RECONCILED);
        }
        return lost.size();
    }

    public int totalLiveCount() {
        int count = 0;
        for (DimensionEntries dim : byDimension.values()) count += dim.liveCount();
        return count;
    }

    public DataFreshness freshnessOf(WorldKey world, int chunkX, int chunkZ) {
        if (!isCurrentSession(world)) return DataFreshness.UNAVAILABLE;
        ChunkCacheEntry entry = getEntry(world.dimensionId(), chunkX, chunkZ);
        return entry == null ? DataFreshness.UNAVAILABLE : entry.freshness();
    }

    public boolean isLive(WorldKey world, int chunkX, int chunkZ) {
        return freshnessOf(world, chunkX, chunkZ) == DataFreshness.LIVE;
    }
}
