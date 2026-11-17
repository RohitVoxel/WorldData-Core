package net.wdc.core.world.governor;

import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.ingestion.ChunkIngestionService;

public final class IngestionGovernorProvider {

    private static volatile IngestionGovernor instance;

    private IngestionGovernorProvider() {}

    public static IngestionGovernor getOrCreate(WDCCache cache, ChunkIngestionService ingestionService, IngestionPerformanceConfig config) {
        IngestionGovernor local = instance;
        if (local == null) {
            synchronized (IngestionGovernorProvider.class) {
                local = instance;
                if (local == null) {
                    local = new IngestionGovernor(cache, ingestionService, config);
                    instance = local;
                }
            }
        }
        return local;
    }

    public static IngestionGovernor get() {
        return instance;
    }

    public static void reset() {
        instance = null;
    }
}
