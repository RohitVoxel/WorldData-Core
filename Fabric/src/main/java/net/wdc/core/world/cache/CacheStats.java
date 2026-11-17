package net.wdc.core.world.cache;

public record CacheStats(
        long hits,
        long misses,
        long rebuilds,
        long evictions,
        int liveEntries,
        int staleEntries
) {}
