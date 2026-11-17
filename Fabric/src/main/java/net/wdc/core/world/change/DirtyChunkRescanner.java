package net.wdc.core.world.change;

import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.governor.GovernorPriority;
import net.wdc.core.world.governor.IngestionGovernor;
import net.wdc.core.world.ingestion.ChunkViewSource;
import net.wdc.core.world.ingestion.MinecraftChunkView;
import net.wdc.core.world.storage.ChunkKey;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class DirtyChunkRescanner {

    public static final int MAX_PENDING = 4096;
    public static final int DEFAULT_PER_TICK = 32;

    private record Dirty(String dimensionId, int chunkX, int chunkZ) {}

    private final Queue<Dirty> queue = new ConcurrentLinkedQueue<>();
    private final Set<Dirty> queued = ConcurrentHashMap.newKeySet();
    private final AtomicInteger size = new AtomicInteger();

    public boolean markDirty(String dimensionId, int chunkX, int chunkZ) {
        if (dimensionId == null || dimensionId.isEmpty()) throw new IllegalArgumentException("dimensionId must not be empty");
        Dirty dirty = new Dirty(dimensionId, chunkX, chunkZ);
        if (queued.contains(dirty)) return true;
        if (size.get() >= MAX_PENDING) return false;
        if (!queued.add(dirty)) return true;
        size.incrementAndGet();
        queue.add(dirty);
        WDCMetrics.increment(WDCMetrics.Counter.CHANGE_DIRTY_REQUESTED);
        return true;
    }

    public int pendingCount() {
        return size.get();
    }

    public int pump(WDCCache cache, IngestionGovernor governor, ChunkViewSource source, int max) {
        return pump(cache, governor, source, max, Long.MAX_VALUE);
    }

    public int pump(WDCCache cache, IngestionGovernor governor, ChunkViewSource source, int max, long budgetNanos) {
        if (cache == null || governor == null || source == null || max <= 0 || budgetNanos <= 0L) return 0;
        long start = System.nanoTime();
        int offered = 0;
        Dirty dirty;
        while (offered < max && System.nanoTime() - start < budgetNanos && (dirty = queue.poll()) != null) {
            size.decrementAndGet();
            queued.remove(dirty);
            WorldKey world = cache.currentWorldKey(dirty.dimensionId());
            ChunkKey key = new ChunkKey(dirty.chunkX(), dirty.chunkZ());
            MinecraftChunkView view = source.viewOf(world, key);
            if (view == null) continue;
            governor.onChunkRefresh(world, key, view, GovernorPriority.ACTIVE_DEMAND);
            WDCMetrics.increment(WDCMetrics.Counter.CHANGE_DIRTY_REREAD);
            offered++;
        }
        return offered;
    }

    public void clear() {
        queue.clear();
        queued.clear();
        size.set(0);
    }
}
