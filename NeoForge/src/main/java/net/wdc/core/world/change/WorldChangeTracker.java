package net.wdc.core.world.change;

import net.wdc.core.api.changes.WDCBlockChange;
import net.wdc.core.api.changes.WDCChangeBatch;
import net.wdc.core.api.changes.WDCChangeFilter;
import net.wdc.core.api.changes.WDCChangeKind;
import net.wdc.core.api.changes.WDCChangeListener;
import net.wdc.core.api.changes.WDCChunkChange;
import net.wdc.core.api.changes.WDCSubscription;
import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.DataFreshness;
import net.wdc.core.world.storage.ChunkKey;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class WorldChangeTracker implements ChangeSink, WDCCache.FreshnessListener {

    public static final int MAX_CHUNKS_PER_BATCH = 4096;
    public static final int MAX_BLOCK_CHANGES_PER_CHUNK = 1024;
    public static final int MAX_BLOCK_CHANGES_PER_BATCH = 16_384;
    public static final int MAX_REBUILDS_PER_FLUSH = 256;
    public static final long REBUILD_BUDGET_NANOS = 3_000_000L;

    private final Object lock = new Object();
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final Map<String, DimensionChanges> pending = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean anySubscriber;
    private volatile boolean anyBlockDetails;
    private volatile ChunkRebuilder rebuilder = ChunkRebuilder.NONE;
    private volatile long rebuildBudgetNanos = REBUILD_BUDGET_NANOS;
    private volatile LongSupplier clock = System::nanoTime;
    private boolean resetPending;

    private static final class RebuildBudget {
        final LongSupplier clock;
        final long deadline;
        int left;

        RebuildBudget(LongSupplier clock, long budgetNanos, int left) {
            this.clock = clock;
            this.deadline = clock.getAsLong() + budgetNanos;
            this.left = left;
        }

        boolean take() {
            if (left <= 0 || clock.getAsLong() >= deadline) return false;
            left--;
            return true;
        }
    }

    private static final class Subscriber implements WDCSubscription {
        private final WorldChangeTracker owner;
        private final WDCChangeFilter filter;
        private final WDCChangeListener listener;
        private volatile boolean active = true;

        Subscriber(WorldChangeTracker owner, WDCChangeFilter filter, WDCChangeListener listener) {
            this.owner = owner;
            this.filter = filter;
            this.listener = listener;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void close() {
            if (!active) return;
            active = false;
            owner.remove(this);
        }
    }

    private static final class DimensionChanges {
        final Map<Long, ChunkChanges> chunks = new LinkedHashMap<>();
        int blockChangeTotal;
        boolean truncated;
    }

    private static final class ChunkChanges {
        final int chunkX;
        final int chunkZ;
        WDCChangeKind lifecycle;
        boolean blocksTouched;
        boolean blocksComplete = true;
        final TreeSet<Integer> sections = new TreeSet<>();
        final Map<Long, int[]> blocks = new LinkedHashMap<>();
        final Map<Long, String[]> blockIds = new LinkedHashMap<>();

        ChunkChanges(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    public WDCSubscription subscribe(WDCChangeFilter filter, WDCChangeListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        Subscriber subscriber = new Subscriber(this, filter == null ? WDCChangeFilter.all() : filter, listener);
        subscribers.add(subscriber);
        refreshFlags();
        return subscriber;
    }

    private void remove(Subscriber subscriber) {
        subscribers.remove(subscriber);
        refreshFlags();
        if (subscribers.isEmpty()) {
            synchronized (lock) {
                pending.clear();
                resetPending = false;
            }
        }
    }

    private void refreshFlags() {
        boolean any = false;
        boolean details = false;
        for (Subscriber subscriber : subscribers) {
            any = true;
            if (subscriber.filter.wantsBlockDetails()) details = true;
        }
        anySubscriber = any;
        anyBlockDetails = details;
    }

    public void setRebuilder(ChunkRebuilder next) {
        this.rebuilder = next == null ? ChunkRebuilder.NONE : next;
    }

    public void setRebuildBudgetNanos(long nanos) {
        if (nanos < 0L) throw new IllegalArgumentException("nanos must not be negative");
        this.rebuildBudgetNanos = nanos;
    }

    public void setClock(LongSupplier next) {
        this.clock = next == null ? System::nanoTime : next;
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    public long batchesDispatched() {
        return sequence.get();
    }

    @Override
    public boolean wantsBlockDetails() {
        return anyBlockDetails;
    }

    @Override
    public void blockChanged(String dimensionId, int x, int y, int z, int oldStateId, int newStateId,
                             String oldBlockId, String newBlockId) {
        if (!anySubscriber) return;
        synchronized (lock) {
            ChunkChanges chunk = chunkFor(dimensionId, x >> 4, z >> 4);
            if (chunk == null) return;
            WDCMetrics.increment(WDCMetrics.Counter.CHANGE_EVENTS_RECORDED);
            chunk.blocksTouched = true;
            chunk.sections.add(y >> 4);
            if (!anyBlockDetails || !chunk.blocksComplete) return;
            DimensionChanges dimension = pending.get(dimensionId);
            long key = pack(x, y, z);
            int[] existing = chunk.blocks.get(key);
            if (existing != null) {
                existing[4] = newStateId;
                chunk.blockIds.get(key)[1] = newBlockId;
                return;
            }
            if (chunk.blocks.size() >= MAX_BLOCK_CHANGES_PER_CHUNK || dimension.blockChangeTotal >= MAX_BLOCK_CHANGES_PER_BATCH) {
                chunk.blocksComplete = false;
                chunk.blocks.clear();
                chunk.blockIds.clear();
                return;
            }
            dimension.blockChangeTotal++;
            chunk.blocks.put(key, new int[]{x, y, z, oldStateId, newStateId});
            chunk.blockIds.put(key, new String[]{oldBlockId, newBlockId});
        }
    }

    @Override
    public void chunkReread(String dimensionId, int chunkX, int chunkZ, int[] changedSectionYs, int count) {
        if (!anySubscriber) return;
        synchronized (lock) {
            ChunkChanges chunk = chunkFor(dimensionId, chunkX, chunkZ);
            if (chunk == null) return;
            WDCMetrics.increment(WDCMetrics.Counter.CHANGE_EVENTS_RECORDED);
            chunk.blocksTouched = true;
            chunk.blocksComplete = false;
            chunk.blocks.clear();
            chunk.blockIds.clear();
            for (int i = 0; i < count; i++) chunk.sections.add(changedSectionYs[i]);
        }
    }

    @Override
    public void onFreshnessChanged(String dimensionId, ChunkKey chunkKey, DataFreshness before, DataFreshness after) {
        if (!anySubscriber) return;
        WDCChangeKind kind = switch (after) {
            case LIVE -> WDCChangeKind.CHUNK_LOADED;
            case STALE -> WDCChangeKind.CHUNK_UNLOADED;
            case UNAVAILABLE -> WDCChangeKind.CHUNK_REMOVED;
        };
        synchronized (lock) {
            ChunkChanges chunk = chunkFor(dimensionId, chunkKey.chunkX(), chunkKey.chunkZ());
            if (chunk == null) return;
            WDCMetrics.increment(WDCMetrics.Counter.CHANGE_EVENTS_RECORDED);
            chunk.lifecycle = kind;
            if (kind != WDCChangeKind.CHUNK_LOADED) {
                chunk.blocksTouched = false;
                chunk.blocks.clear();
                chunk.blockIds.clear();
                chunk.blocksComplete = true;
                chunk.sections.clear();
            }
        }
    }

    @Override
    public void onCleared() {
        if (!anySubscriber) return;
        synchronized (lock) {
            pending.clear();
            resetPending = true;
        }
    }

    private ChunkChanges chunkFor(String dimensionId, int chunkX, int chunkZ) {
        DimensionChanges dimension = pending.computeIfAbsent(dimensionId, id -> new DimensionChanges());
        long key = ChunkKey.pack(chunkX, chunkZ);
        ChunkChanges chunk = dimension.chunks.get(key);
        if (chunk != null) return chunk;
        if (dimension.chunks.size() >= MAX_CHUNKS_PER_BATCH) {
            dimension.truncated = true;
            return null;
        }
        chunk = new ChunkChanges(chunkX, chunkZ);
        dimension.chunks.put(key, chunk);
        return chunk;
    }

    public int flush(long tick) {
        return flush(tick, Long.MAX_VALUE);
    }

    public int flush(long tick, long externalBudgetNanos) {
        List<WDCChangeBatch> batches = new ArrayList<>();
        Map<String, DimensionChanges> snapshot;
        boolean reset;
        synchronized (lock) {
            snapshot = new LinkedHashMap<>(pending);
            pending.clear();
            reset = resetPending;
            resetPending = false;
        }
        if (reset) {
            batches.add(new WDCChangeBatch(WDCChangeBatch.ALL_DIMENSIONS, sequence.incrementAndGet(), tick, List.of(), false, true));
        }
        RebuildBudget budget = new RebuildBudget(clock, Math.max(0L, Math.min(rebuildBudgetNanos, externalBudgetNanos)), MAX_REBUILDS_PER_FLUSH);
        for (Map.Entry<String, DimensionChanges> entry : snapshot.entrySet()) {
            WDCChangeBatch batch = build(entry.getKey(), entry.getValue(), tick, budget);
            if (batch != null) batches.add(batch);
        }
        for (WDCChangeBatch batch : batches) dispatch(batch);
        return batches.size();
    }

    private boolean rebuild(String dimensionId, ChunkChanges chunk, RebuildBudget budget) {
        boolean dataPresent = chunk.lifecycle == null || chunk.lifecycle == WDCChangeKind.CHUNK_LOADED;
        if (!dataPresent) return true;
        if (!budget.take()) {
            WDCMetrics.increment(WDCMetrics.Counter.CHANGE_REBUILD_DEFERRED);
            return false;
        }
        try {
            rebuilder.rebuild(dimensionId, chunk.chunkX, chunk.chunkZ);
            WDCMetrics.increment(WDCMetrics.Counter.CHANGE_CHUNKS_REBUILT);
            return true;
        } catch (RuntimeException failure) {
            WDCMetrics.increment(WDCMetrics.Counter.CHANGE_LISTENER_FAILURES);
            return false;
        }
    }

    private WDCChangeBatch build(String dimensionId, DimensionChanges dimension, long tick, RebuildBudget budget) {
        List<WDCChunkChange> changes = new ArrayList<>(dimension.chunks.size());
        for (Map.Entry<Long, ChunkChanges> entry : dimension.chunks.entrySet()) {
            ChunkChanges chunk = entry.getValue();
            Set<WDCChangeKind> kinds = EnumSet.noneOf(WDCChangeKind.class);
            if (chunk.lifecycle != null) kinds.add(chunk.lifecycle);
            if (chunk.blocksTouched) kinds.add(WDCChangeKind.BLOCKS_CHANGED);
            if (kinds.isEmpty()) continue;
            List<WDCBlockChange> blocks = new ArrayList<>(chunk.blocks.size());
            for (Map.Entry<Long, int[]> block : chunk.blocks.entrySet()) {
                int[] v = block.getValue();
                if (v[3] == v[4]) continue;
                String[] ids = chunk.blockIds.get(block.getKey());
                blocks.add(new WDCBlockChange(v[0], v[1], v[2], v[3], v[4], ids[0], ids[1]));
            }
            changes.add(new WDCChunkChange(chunk.chunkX, chunk.chunkZ, kinds,
                    new ArrayList<>(chunk.sections), blocks, chunk.blocksComplete, rebuild(dimensionId, chunk, budget)));
        }
        if (changes.isEmpty() && !dimension.truncated) return null;
        if (dimension.truncated) WDCMetrics.increment(WDCMetrics.Counter.CHANGE_BATCHES_TRUNCATED);
        return new WDCChangeBatch(dimensionId, sequence.incrementAndGet(), tick, changes, dimension.truncated, false);
    }

    private void dispatch(WDCChangeBatch batch) {
        for (Subscriber subscriber : subscribers) {
            if (!subscriber.active || !subscriber.filter.matchesDimension(batch.dimensionId())) continue;
            WDCChangeBatch view = viewFor(subscriber.filter, batch);
            if (view == null) continue;
            try {
                subscriber.listener.onChanges(view);
                WDCMetrics.increment(WDCMetrics.Counter.CHANGE_BATCHES_DISPATCHED);
            } catch (Throwable failure) {
                WDCMetrics.increment(WDCMetrics.Counter.CHANGE_LISTENER_FAILURES);
            }
        }
    }

    private static WDCChangeBatch viewFor(WDCChangeFilter filter, WDCChangeBatch batch) {
        if (filter.isUnrestricted() || batch.reset()) return batch;
        List<WDCChunkChange> kept = new ArrayList<>(batch.chunks().size());
        for (WDCChunkChange chunk : batch.chunks()) {
            Set<WDCChangeKind> kinds = EnumSet.noneOf(WDCChangeKind.class);
            for (WDCChangeKind kind : chunk.kinds()) if (filter.matchesKind(kind)) kinds.add(kind);
            if (kinds.isEmpty()) continue;
            boolean details = filter.wantsBlockDetails() && kinds.contains(WDCChangeKind.BLOCKS_CHANGED);
            kept.add(new WDCChunkChange(chunk.chunkX(), chunk.chunkZ(), kinds, chunk.changedSectionYs(),
                    details ? chunk.blockChanges() : List.of(), details ? chunk.blockDetailsComplete() : false,
                    chunk.derivedRebuilt()));
        }
        if (kept.isEmpty() && !batch.truncated()) return null;
        return new WDCChangeBatch(batch.dimensionId(), batch.sequence(), batch.tick(), kept, batch.truncated(), false);
    }

    public void clearPending() {
        synchronized (lock) {
            pending.clear();
            resetPending = false;
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }
}
