package net.wdc.core.world.cache;

import net.wdc.core.world.storage.ChunkKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class DimensionEntries {

    private final Map<ChunkKey, ChunkCacheEntry> map = new ConcurrentHashMap<>();
    private final Set<ChunkCacheEntry> staleEntries = ConcurrentHashMap.newKeySet();
    private volatile int liveCount;
    private volatile int staleCount;

    ChunkCacheEntry get(ChunkKey key) {
        return map.get(key);
    }

    Collection<ChunkCacheEntry> values() {
        return map.values();
    }

    boolean isEmpty() {
        return map.isEmpty();
    }

    int liveCount() {
        return liveCount;
    }

    int staleCount() {
        return staleCount;
    }

    List<ChunkCacheEntry> staleSnapshot() {
        return new ArrayList<>(staleEntries);
    }

    synchronized void put(ChunkKey key, ChunkCacheEntry entry) {
        ChunkCacheEntry previous = map.put(key, entry);
        if (previous != null) uncount(previous);
        count(entry);
    }

    synchronized boolean remove(ChunkKey key, ChunkCacheEntry entry) {
        if (!map.remove(key, entry)) return false;
        uncount(entry);
        return true;
    }

    synchronized boolean remove(ChunkKey key) {
        ChunkCacheEntry previous = map.remove(key);
        if (previous == null) return false;
        uncount(previous);
        return true;
    }

    synchronized void setFreshness(ChunkCacheEntry entry, DataFreshness freshness) {
        if (map.get(entry.key().chunk()) != entry) {
            entry.setFreshness(freshness);
            return;
        }
        uncount(entry);
        entry.setFreshness(freshness);
        count(entry);
    }

    private void count(ChunkCacheEntry entry) {
        switch (entry.freshness()) {
            case LIVE -> liveCount++;
            case STALE -> {
                staleCount++;
                staleEntries.add(entry);
            }
            case UNAVAILABLE -> { }
        }
    }

    private void uncount(ChunkCacheEntry entry) {
        switch (entry.freshness()) {
            case LIVE -> liveCount--;
            case STALE -> {
                staleCount--;
                staleEntries.remove(entry);
            }
            case UNAVAILABLE -> { }
        }
    }
}
