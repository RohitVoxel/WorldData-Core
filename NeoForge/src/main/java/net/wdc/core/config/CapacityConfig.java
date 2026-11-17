package net.wdc.core.config;

public final class CapacityConfig {

    public static final int MAX_INGEST_QUEUE = 65_536;
    public static final int ABSOLUTE_MAX_STALE = 32_768;
    public static final int ABSOLUTE_MAX_LIVE = 32_768;
    public static final int MAX_STALE = ABSOLUTE_MAX_STALE;
    public static final int DEFAULT_INGEST_QUEUE_LENGTH_MAX = 32_768;
    public static final int PREVIOUS_DEFAULT_INGEST_QUEUE_LENGTH_MAX = 25_000;
    public static final int PREVIOUS_DEFAULT_STALE_MAX = 256;
    public static final int PREVIOUS_DEFAULT_STALE_MAX_V2 = 4096;
    public static final int DEFAULT_STALE_MAX = 1024;
    public static final int DEFAULT_LIVE_MAX = 4096;
    public static final int DEFAULT_STALE_CEILING = 16_384;
    public static final int DEFAULT_LIVE_CEILING = 16_384;
    public static final int CURRENT_SCHEMA = 4;
    public static final int MAX_EVICTION_BATCH = 4096;
    public static final int MAX_SPILL_QUEUE = 65_536;

    public int ingestQueueLengthMax = DEFAULT_INGEST_QUEUE_LENGTH_MAX;
    public int schema;
    public int liveMax = DEFAULT_LIVE_MAX;
    public int staleMax = DEFAULT_STALE_MAX;
    public int staleMaxCeiling = DEFAULT_STALE_CEILING;
    public int liveMaxCeiling = DEFAULT_LIVE_CEILING;
    public int evictionBatch = 32;
    public int spillQueueLength = 256;

    public void validate() {
        if (ingestQueueLengthMax < 1 || ingestQueueLengthMax > MAX_INGEST_QUEUE) {
            throw new IllegalArgumentException("ingestQueueLengthMax must be within 1.." + MAX_INGEST_QUEUE);
        }
        if (staleMaxCeiling < 1 || staleMaxCeiling > ABSOLUTE_MAX_STALE) {
            throw new IllegalArgumentException("staleMaxCeiling must be within 1.." + ABSOLUTE_MAX_STALE);
        }
        if (liveMaxCeiling < 1 || liveMaxCeiling > ABSOLUTE_MAX_LIVE) {
            throw new IllegalArgumentException("liveMaxCeiling must be within 1.." + ABSOLUTE_MAX_LIVE);
        }
        if (liveMax < 1 || liveMax > liveMaxCeiling) throw new IllegalArgumentException("liveMax must be within 1.." + liveMaxCeiling);
        if (staleMax < 0 || staleMax > staleMaxCeiling) throw new IllegalArgumentException("staleMax must be within 0.." + staleMaxCeiling);
        if (evictionBatch < 1 || evictionBatch > MAX_EVICTION_BATCH) {
            throw new IllegalArgumentException("evictionBatch must be within 1.." + MAX_EVICTION_BATCH);
        }
        if (spillQueueLength < 1 || spillQueueLength > MAX_SPILL_QUEUE) {
            throw new IllegalArgumentException("spillQueueLength must be within 1.." + MAX_SPILL_QUEUE);
        }
    }

    public boolean migrateLegacy() {
        if (schema >= CURRENT_SCHEMA) return false;
        int from = schema;
        schema = CURRENT_SCHEMA;
        if ((from < 3 && staleMax == PREVIOUS_DEFAULT_STALE_MAX_V2) || (from < 2 && staleMax == PREVIOUS_DEFAULT_STALE_MAX)) staleMax = DEFAULT_STALE_MAX;
        if (ingestQueueLengthMax == PREVIOUS_DEFAULT_INGEST_QUEUE_LENGTH_MAX) ingestQueueLengthMax = DEFAULT_INGEST_QUEUE_LENGTH_MAX;
        clampToCeilings();
        return true;
    }

    public boolean clampToCeilings() {
        boolean changed = false;
        int staleCeiling = Math.max(1, Math.min(ABSOLUTE_MAX_STALE, staleMaxCeiling));
        if (staleCeiling != staleMaxCeiling) { staleMaxCeiling = staleCeiling; changed = true; }
        int liveCeiling = Math.max(1, Math.min(ABSOLUTE_MAX_LIVE, liveMaxCeiling));
        if (liveCeiling != liveMaxCeiling) { liveMaxCeiling = liveCeiling; changed = true; }
        if (liveMax > liveMaxCeiling) { liveMax = liveMaxCeiling; changed = true; }
        if (liveMax < 1) { liveMax = DEFAULT_LIVE_MAX; changed = true; }
        if (staleMax > staleMaxCeiling) { staleMax = staleMaxCeiling; changed = true; }
        if (ingestQueueLengthMax > MAX_INGEST_QUEUE) { ingestQueueLengthMax = MAX_INGEST_QUEUE; changed = true; }
        return changed;
    }

    public CapacityConfig copy() {
        CapacityConfig copy = new CapacityConfig();
        copy.schema = schema;
        copy.ingestQueueLengthMax = ingestQueueLengthMax;
        copy.liveMax = liveMax;
        copy.staleMax = staleMax;
        copy.staleMaxCeiling = staleMaxCeiling;
        copy.liveMaxCeiling = liveMaxCeiling;
        copy.evictionBatch = evictionBatch;
        copy.spillQueueLength = spillQueueLength;
        return copy;
    }
}
