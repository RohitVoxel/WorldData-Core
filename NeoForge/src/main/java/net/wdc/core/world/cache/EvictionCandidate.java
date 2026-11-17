package net.wdc.core.world.cache;

import net.wdc.core.world.storage.ChunkKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

record EvictionCandidate(long accessTick, int chunkX, int chunkZ, String dimensionId, ChunkCacheEntry entry) {

    static final Comparator<EvictionCandidate> OLDEST_FIRST = Comparator
            .comparingLong(EvictionCandidate::accessTick)
            .thenComparingInt(EvictionCandidate::chunkX)
            .thenComparingInt(EvictionCandidate::chunkZ)
            .thenComparing(EvictionCandidate::dimensionId, Comparator.nullsFirst(Comparator.<String>naturalOrder()));

    static EvictionCandidate of(String dimensionId, ChunkCacheEntry entry) {
        ChunkKey chunk = entry.key().chunk();
        return new EvictionCandidate(entry.lastAccessTick(), chunk.chunkX(), chunk.chunkZ(), dimensionId, entry);
    }

    static List<EvictionCandidate> snapshot(String dimensionId, Collection<ChunkCacheEntry> entries) {
        List<EvictionCandidate> snapshot = new ArrayList<>(entries.size());
        for (ChunkCacheEntry entry : entries) snapshot.add(of(dimensionId, entry));
        return snapshot;
    }

    static List<EvictionCandidate> oldestFirst(String dimensionId, Collection<ChunkCacheEntry> entries) {
        List<EvictionCandidate> ordered = snapshot(dimensionId, entries);
        ordered.sort(OLDEST_FIRST);
        return ordered;
    }
}
