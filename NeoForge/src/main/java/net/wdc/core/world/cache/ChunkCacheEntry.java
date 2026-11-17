package net.wdc.core.world.cache;

import net.wdc.core.world.storage.WDCChunk;

final class ChunkCacheEntry {

    private final CachedChunkKey key;
    private volatile WDCChunk data;
    private volatile ChunkFingerprint lastValidatedFingerprint;
    private volatile boolean fingerprintDirty;
    private volatile DataFreshness freshness;
    private volatile long lastAccessTick;
    private volatile long lastAccessNanos;
    private volatile DataResult<WDCChunk> cachedResult;
    private static final java.util.concurrent.atomic.AtomicLongFieldUpdater<ChunkCacheEntry> TICK =
            java.util.concurrent.atomic.AtomicLongFieldUpdater.newUpdater(ChunkCacheEntry.class, "lastAccessTick");
    private static final java.util.concurrent.atomic.AtomicLongFieldUpdater<ChunkCacheEntry> NANOS =
            java.util.concurrent.atomic.AtomicLongFieldUpdater.newUpdater(ChunkCacheEntry.class, "lastAccessNanos");

    ChunkCacheEntry(CachedChunkKey key, WDCChunk data, ChunkFingerprint fingerprint, DataFreshness freshness, long accessTick, long accessNanos) {
        this.key = key;
        this.data = data;
        this.lastValidatedFingerprint = fingerprint;
        this.freshness = freshness;
        this.lastAccessTick = accessTick;
        this.lastAccessNanos = accessNanos;
    }

    CachedChunkKey key() {
        return key;
    }

    WDCChunk data() {
        return data;
    }

    void setData(WDCChunk data) {
        this.data = data;
    }

    ChunkFingerprint lastValidatedFingerprint() {
        return lastValidatedFingerprint;
    }

    void setLastValidatedFingerprint(ChunkFingerprint fingerprint) {
        this.lastValidatedFingerprint = fingerprint;
        this.fingerprintDirty = false;
    }

    boolean fingerprintDirty() {
        return fingerprintDirty;
    }

    void markFingerprintDirty() {
        this.fingerprintDirty = true;
    }

    DataFreshness freshness() {
        return freshness;
    }

    void setFreshness(DataFreshness freshness) {
        this.freshness = freshness;
    }

    long lastAccessTick() {
        return lastAccessTick;
    }

    long lastAccessNanos() {
        return lastAccessNanos;
    }

    void touch(long accessTick, long accessNanos) {
        TICK.lazySet(this, accessTick);
        NANOS.lazySet(this, accessNanos);
    }

    DataResult<WDCChunk> resultFor(DataFreshness status, WDCChunk chunk, ChunkFingerprint fingerprint) {
        DataResult<WDCChunk> cached = cachedResult;
        if (cached != null && cached.status() == status && cached.value() == chunk && cached.fingerprint() == fingerprint) {
            return cached;
        }
        DataResult<WDCChunk> built = status == DataFreshness.LIVE
                ? DataResult.live(chunk, fingerprint) : DataResult.stale(chunk, fingerprint);
        cachedResult = built;
        return built;
    }
}
