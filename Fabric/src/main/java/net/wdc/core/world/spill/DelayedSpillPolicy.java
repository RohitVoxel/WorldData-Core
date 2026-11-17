package net.wdc.core.world.spill;

import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.debug.WDCOps;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.DataFreshness;
import net.wdc.core.world.cache.DataResult;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.demand.WDCDemandService;
import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.ChunkKey;
import net.wdc.core.world.storage.ChunkRegistryProvider;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class DelayedSpillPolicy {
    public static final long DEFAULT_GRACE_NANOS = 4_500_000_000L;
    private static final int DEFAULT_MAX_FILES_PER_DIMENSION = 2048;
    private static final int MAX_DELETIONS_PER_PRUNE = 64;
    private static final long RESTORE_MISS_TTL_NANOS = 30_000_000_000L;
    private static final int MAX_MISS_ENTRIES = 8192;

    private final WDCCache cache;
    private final ChunkSpillStore store;
    private final GlobalBlockStateRegistry registry;
    private final WDCIoExecutor ioExecutor;
    private final long graceNanos;
    private final int maxFilesPerDimension;

    private final Map<SpillKey, Long> pendingUnload = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedDeque<PendingStamp> pendingOrder = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private record PendingStamp(SpillKey key, long stamp) {}
    private final Map<SpillKey, Long> restoreMisses = new ConcurrentHashMap<>();
    private final Set<SpillKey> inFlightSpills = new HashSet<>();
    private final Set<SpillKey> inFlightRestores = new HashSet<>();
    private final Queue<SpillCompletion> spillCompletions = new ConcurrentLinkedQueue<>();
    private final Queue<RestoreCompletion> restoreCompletions = new ConcurrentLinkedQueue<>();

    static int pruneEveryNWrites = 32;
    private final Map<String, Integer> writesSincePrune = new HashMap<>();

    private WDCDemandService demandService;
    private final SpillIoCounters counters = new SpillIoCounters();

    public DelayedSpillPolicy(WDCCache cache, ChunkSpillStore store,
                              GlobalBlockStateRegistry registry, long graceNanos) {
        this(cache, store, registry, graceNanos, new WDCIoExecutor(), DEFAULT_MAX_FILES_PER_DIMENSION);
    }

    public DelayedSpillPolicy(WDCCache cache, ChunkSpillStore store, GlobalBlockStateRegistry registry,
                              long graceNanos, WDCIoExecutor ioExecutor) {
        this(cache, store, registry, graceNanos, ioExecutor, DEFAULT_MAX_FILES_PER_DIMENSION);
    }

    public DelayedSpillPolicy(WDCCache cache, ChunkSpillStore store, GlobalBlockStateRegistry registry,
                              long graceNanos, WDCIoExecutor ioExecutor, int maxFilesPerDimension) {
        if (cache == null || store == null || registry == null || ioExecutor == null) {
            throw new IllegalArgumentException("cache/store/registry/ioExecutor must not be null");
        }
        if (graceNanos < 0) throw new IllegalArgumentException("graceNanos must be >= 0");
        if (maxFilesPerDimension < 1) throw new IllegalArgumentException("maxFilesPerDimension must be >= 1");
        this.cache = cache;
        this.store = store;
        this.registry = registry;
        this.graceNanos = graceNanos;
        this.ioExecutor = ioExecutor;
        this.maxFilesPerDimension = maxFilesPerDimension;
    }

    private void pruneIfNeeded(String scope) {
        int n = writesSincePrune.merge(scope, 1, Integer::sum);
        if (n < pruneEveryNWrites) return;
        writesSincePrune.put(scope, 0);
        long start = System.nanoTime();
        try {
            store.prune(scope, maxFilesPerDimension, MAX_DELETIONS_PER_PRUNE);
        } finally {
            WDCMetrics.record(WDCMetrics.Timer.SPILL_PRUNE, System.nanoTime() - start);
        }
    }

    public void setDemandService(WDCDemandService demandService) { this.demandService = demandService; }

    public int shutdownFlush(long graceMillis) {
        long deadline = System.nanoTime() + Math.max(0L, graceMillis) * 1_000_000L;
        int written = 0;
        for (SpillKey key : new java.util.ArrayList<>(pendingUnload.keySet())) {
            if (System.nanoTime() >= deadline || !WDCSpillRoots.isUsable()) break;
            WDCChunk data = cache.query(key.world(), key.x(), key.z()).value();
            if (data == null) continue;
            try {
                WDCChunk copy = data.detachedCopy();
                try {
                    store.write(ChunkSpillStore.capture(copy, registry), WDCSpillRoots.scope(key.world().dimensionId()));
                } finally {
                    copy.releaseToPool();
                }
                written++;
            } catch (RuntimeException failed) {
                continue;
            }
        }
        pendingUnload.clear();
        pendingOrder.clear();
        while ((ioExecutor.inFlight() > 0 || ioExecutor.queueDepth() > 0) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(2L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        store.close();
        return written;
    }

    public void onUnloaded(WorldKey world, int chunkX, int chunkZ) {
        SpillKey key = new SpillKey(world, chunkX, chunkZ);
        restoreMisses.remove(key);
        requeue(key, now());
    }

    private void requeue(SpillKey key, long stamp) {
        pendingUnload.put(key, stamp);
        pendingOrder.addLast(new PendingStamp(key, stamp));
    }

    public void tick(long budgetNanos) {
        long now = now();
        long start = System.nanoTime();
        while (System.nanoTime() - start < budgetNanos) {
            PendingStamp head = pendingOrder.peekFirst();
            if (head == null) break;
            SpillKey key = head.key();
            Long current = pendingUnload.get(key);
            if (current == null || current != head.stamp()) {
                pendingOrder.pollFirst();
                continue;
            }
            if (now - head.stamp() < graceNanos) break;
            pendingOrder.pollFirst();
            if (!cache.isCurrentSession(key.world()) || !WDCSpillRoots.isUsable()) {
                pendingUnload.remove(key);
                continue;
            }
            ChunkKey chunk = new ChunkKey(key.x(), key.z());
            if (demandService != null && demandService.interestedQueryCount(key.world(), chunk) > 0) {
                requeue(key, now);
                continue;
            }
            DataResult<WDCChunk> state = cache.query(key.world(), key.x(), key.z());
            if (state.status() == DataFreshness.LIVE) {
                pendingUnload.remove(key);
                continue;
            }
            WDCChunk data = state.value();
            if (data == null) {
                pendingUnload.remove(key);
                continue;
            }
            if (inFlightSpills.contains(key)) {
                requeue(key, now);
                continue;
            }
            if (ioExecutor.queueDepth() >= ioExecutor.queueCapacity()) {
                pendingOrder.addFirst(head);
                counters.deferred.incrementAndGet();
                break;
            }
            pendingUnload.remove(key);
            final WDCChunk detached = data.detachedCopy();
            final long generation = data.version();
            final String scope = WDCSpillRoots.scope(key.world().dimensionId());
            boolean reserved = inFlightSpills.add(key);
            boolean accepted = reserved && ioExecutor.trySubmit(() -> {
                RuntimeException failed = null;
                long writeStart = System.nanoTime();
                try {
                    store.write(ChunkSpillStore.capture(detached, registry), scope);
                } catch (RuntimeException e) {
                    failed = e;
                } finally {
                    detached.releaseToPool();
                    WDCMetrics.record(WDCMetrics.Timer.SPILL_WRITE, System.nanoTime() - writeStart);
                }
                pruneIfNeeded(scope);
                spillCompletions.add(new SpillCompletion(key, generation, failed));
            });
            if (!accepted) {
                if (reserved) inFlightSpills.remove(key);
                counters.deferred.incrementAndGet();
                requeue(key, now);
            } else {
                counters.spillQueued.incrementAndGet();
            }
        }
        long spent = System.nanoTime() - start;
        drainCompletions(budgetNanos == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, budgetNanos - spent));
    }

    public void drainCompletions(long budgetNanos) {
        long start = System.nanoTime();
        while (System.nanoTime() - start < budgetNanos) {
            SpillCompletion spill = spillCompletions.poll();
            if (spill == null) break;
            applySpillCompletion(spill);
        }
        while (System.nanoTime() - start < budgetNanos) {
            RestoreCompletion restore = restoreCompletions.poll();
            if (restore == null) break;
            applyRestoreCompletion(restore);
        }
    }

    private void applySpillCompletion(SpillCompletion result) {
        inFlightSpills.remove(result.key());
        SpillKey key = result.key();
        if (result.failure() == null) restoreMisses.remove(key);
        if (result.failure() != null) {
            counters.spillFailed.incrementAndGet();
            return;
        }
        if (!cache.isCurrentSession(key.world())) {
            counters.spillDiscarded.incrementAndGet();
            return;
        }
        DataResult<WDCChunk> current = cache.query(key.world(), key.x(), key.z());
        WDCChunk live = current.value();
        boolean stillApplicable = live != null
                && live.version() == result.generation()
                && current.status() != DataFreshness.LIVE;
        if (!stillApplicable) {
            counters.spillDiscarded.incrementAndGet();
            return;
        }
        cache.evictEntry(key.world(), key.x(), key.z());
        counters.spillCompleted.incrementAndGet();
        WDCOps.record(WDCOps.Kind.SPILL);
    }

    public boolean restore(WorldKey world, int chunkX, int chunkZ) {
        if (!cache.isCurrentSession(world) || !WDCSpillRoots.isUsable()) return false;
        SpillKey key = new SpillKey(world, chunkX, chunkZ);
        if (inFlightRestores.contains(key)) return true;
        Long missedAt = restoreMisses.get(key);
        if (missedAt != null) {
            if (now() - missedAt < RESTORE_MISS_TTL_NANOS) {
                counters.restoreSuppressed.incrementAndGet();
                return false;
            }
            restoreMisses.remove(key);
        }
        final String scope = WDCSpillRoots.scope(world.dimensionId());
        boolean reserved = inFlightRestores.add(key);
        boolean accepted = reserved && ioExecutor.trySubmit(() -> {
            RuntimeException failed = null;
            WDCChunk chunk = null;
            boolean missing = false;
            long ioStart = WDCMetrics.startNanos();
            try {
                if (!store.exists(scope, chunkX, chunkZ)) {
                    missing = true;
                } else {
                    chunk = store.read(scope, chunkX, chunkZ, registry);
                    if (chunk == null) failed = new IllegalStateException("spill file unreadable");
                }
            } catch (RuntimeException e) {
                failed = e;
            } finally {
                WDCMetrics.record(WDCMetrics.Timer.SPILL_READ, System.nanoTime() - ioStart);
            }
            restoreCompletions.add(new RestoreCompletion(key, chunk, failed, missing));
        });
        if (!accepted) {
            if (reserved) inFlightRestores.remove(key);
            counters.deferred.incrementAndGet();
            return false;
        }
        counters.restoreQueued.incrementAndGet();
        return true;
    }

    private void applyRestoreCompletion(RestoreCompletion result) {
        inFlightRestores.remove(result.key());
        SpillKey key = result.key();
        if (result.missing()) {
            counters.restoreMissed.incrementAndGet();
            if (restoreMisses.size() >= MAX_MISS_ENTRIES) restoreMisses.clear();
            restoreMisses.put(key, now());
            return;
        }
        if (result.failure() != null || result.chunk() == null) {
            counters.restoreFailed.incrementAndGet();
            return;
        }
        if (!cache.isCurrentSession(key.world())) {
            counters.restoreDiscarded.incrementAndGet();
            return;
        }
        DataResult<WDCChunk> current = cache.query(key.world(), key.x(), key.z());
        if (current.status() != DataFreshness.UNAVAILABLE) {
            counters.restoreDiscarded.incrementAndGet();
            return;
        }
        ChunkKey chunk = new ChunkKey(key.x(), key.z());
        ChunkRegistryProvider.forDimension(key.world().dimensionId()).put(chunk, result.chunk());
        cache.injectRestored(key.world(), key.x(), key.z(), result.chunk());
        counters.restoreCompleted.incrementAndGet();
        WDCOps.record(WDCOps.Kind.SPILL);
    }

    public boolean hasSpill(WorldKey world, int chunkX, int chunkZ) {
        if (!WDCSpillRoots.isUsable()) return false;
        return store.exists(WDCSpillRoots.scope(world.dimensionId()), chunkX, chunkZ);
    }

    public SpillIoStats ioStats() {
        return new SpillIoStats(
                counters.spillQueued.get(), counters.spillCompleted.get(),
                counters.spillFailed.get(), counters.spillDiscarded.get(),
                counters.restoreQueued.get(), counters.restoreCompleted.get(),
                counters.restoreFailed.get(), counters.restoreDiscarded.get(),
                counters.restoreMissed.get(), counters.restoreSuppressed.get(),
                counters.deferred.get(), ioExecutor.queueDepth(), ioExecutor.inFlight(),
                ioExecutor.queueCapacity());
    }

    public void setSpillQueueCapacity(int capacity) { ioExecutor.setQueueCapacity(capacity); }

    public int spillQueueCapacity() { return ioExecutor.queueCapacity(); }

    public void shutdown() {
        ioExecutor.shutdown();
    }

    public void reset() {
        pendingUnload.clear();
        pendingOrder.clear();
        inFlightSpills.clear();
        inFlightRestores.clear();
        spillCompletions.clear();
        restoreCompletions.clear();
        restoreMisses.clear();
    }

    private static long now() { return System.nanoTime(); }

    private record SpillKey(WorldKey world, int x, int z) {}
    private record SpillCompletion(SpillKey key, long generation, RuntimeException failure) {}
    private record RestoreCompletion(SpillKey key, WDCChunk chunk, RuntimeException failure, boolean missing) {}

    private static final class SpillIoCounters {
        final java.util.concurrent.atomic.AtomicLong spillQueued = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong spillCompleted = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong spillFailed = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong spillDiscarded = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong restoreQueued = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong restoreCompleted = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong restoreFailed = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong restoreDiscarded = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong restoreMissed = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong restoreSuppressed = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong deferred = new java.util.concurrent.atomic.AtomicLong();
    }
}
