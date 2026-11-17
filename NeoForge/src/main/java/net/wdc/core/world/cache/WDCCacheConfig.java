package net.wdc.core.world.cache;

public final class WDCCacheConfig {
    public static final int DEFAULT_MAX_STALE_CHUNKS_PER_DIMENSION = 1024;
    public static final int MAX_STALE_CHUNKS_PER_DIMENSION = 32_768;
    public static final int DEFAULT_EVICTION_BATCH = 32;

    private volatile int maxStaleChunksPerDimension;
    private volatile int evictionBatch = DEFAULT_EVICTION_BATCH;

    public WDCCacheConfig() { this(DEFAULT_MAX_STALE_CHUNKS_PER_DIMENSION); }

    public WDCCacheConfig(int maxStaleChunksPerDimension) {
        setMaxStaleChunksPerDimension(maxStaleChunksPerDimension);
    }

    public int maxStaleChunksPerDimension() { return maxStaleChunksPerDimension; }

    public void setMaxStaleChunksPerDimension(int value) {
        if (value < 0 || value > MAX_STALE_CHUNKS_PER_DIMENSION) {
            throw new IllegalArgumentException("maxStaleChunksPerDimension must be within 0.." + MAX_STALE_CHUNKS_PER_DIMENSION
                    + ", got " + value);
        }
        this.maxStaleChunksPerDimension = value;
    }

    public int evictionBatch() { return evictionBatch; }

    public void setEvictionBatch(int value) {
        if (value < 1) throw new IllegalArgumentException("evictionBatch must be >= 1, got " + value);
        this.evictionBatch = value;
    }
}
