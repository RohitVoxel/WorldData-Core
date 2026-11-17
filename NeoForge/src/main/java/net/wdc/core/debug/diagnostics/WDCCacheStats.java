package net.wdc.core.debug.diagnostics;

public record WDCCacheStats(long hits, long misses, long rebuilds, long evictions, int liveEntries, int staleEntries) {

    public long lookups() {
        return hits + misses;
    }

    public double hitRate() {
        long total = lookups();
        return total == 0L ? 0.0 : hits / (double) total;
    }

    public double missRate() {
        long total = lookups();
        return total == 0L ? 0.0 : misses / (double) total;
    }

    public int entries() {
        return liveEntries + staleEntries;
    }
}
