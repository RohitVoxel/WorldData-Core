package net.wdc.core.api.changes;

import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.change.DirtyChunkRescanner;
import net.wdc.core.world.change.WorldChangeTracker;
import net.wdc.core.world.storage.WDCChunk;

public final class ChangesApi {

    private final WorldChangeTracker tracker;
    private final DirtyChunkRescanner rescanner;
    private final WDCCache cache;

    public ChangesApi(WorldChangeTracker tracker, DirtyChunkRescanner rescanner, WDCCache cache) {
        this.tracker = tracker;
        this.rescanner = rescanner;
        this.cache = cache;
    }

    public WDCSubscription subscribe(WDCChangeListener listener) {
        return tracker.subscribe(WDCChangeFilter.all(), listener);
    }

    public WDCSubscription subscribe(WDCChangeFilter filter, WDCChangeListener listener) {
        return tracker.subscribe(filter, listener);
    }

    public long chunkVersion(String dimensionId, int chunkX, int chunkZ) {
        if (cache == null || dimensionId == null) return -1L;
        WorldKey world = cache.currentWorldKey(dimensionId);
        WDCCache.EntryView view = cache.inspect(world, chunkX, chunkZ);
        WDCChunk chunk = view == null ? null : view.chunk();
        return chunk == null ? -1L : chunk.version();
    }

    public boolean markChunkDirty(String dimensionId, int chunkX, int chunkZ) {
        return rescanner.markDirty(dimensionId, chunkX, chunkZ);
    }

    public boolean markBlockDirty(String dimensionId, int blockX, int blockY, int blockZ) {
        return rescanner.markDirty(dimensionId, blockX >> 4, blockZ >> 4);
    }
}
