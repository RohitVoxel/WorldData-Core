package net.wdc.core.debug.diagnostics;

import net.wdc.core.api.request.WDCFreshness;
import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.debug.LoadReport;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.CacheStats;
import net.wdc.core.world.cache.DataFreshness;
import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.WDCSection;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class CoreDiagnostics {

    private final WDCCache cache;
    private final GlobalBlockStateRegistry registry;

    public CoreDiagnostics(WDCCache cache, GlobalBlockStateRegistry registry) {
        this.cache = cache;
        this.registry = registry;
    }

    public boolean available() {
        return cache != null;
    }

    public WDCCacheStats cacheStats() {
        if (cache == null) return new WDCCacheStats(0L, 0L, 0L, 0L, 0, 0);
        CacheStats stats = cache.stats();
        return new WDCCacheStats(stats.hits(), stats.misses(), stats.rebuilds(), stats.evictions(),
                stats.liveEntries(), stats.staleEntries());
    }

    public long memoryEstimateBytes() {
        return scan()[0];
    }

    public WDCDiagnosticsSnapshot snapshot() {
        long[] scanned = scan();
        WDCMetrics.TimerSnapshot ingest = WDCMetrics.snapshot(WDCMetrics.Timer.INGESTION);
        return new WDCDiagnosticsSnapshot(
                cacheStats(),
                LoadReport.capture(),
                scanned[0],
                (int) scanned[1],
                registry == null ? 0 : registry.size(),
                ingest.count(),
                ingest.averageNanos() / 1_000_000.0,
                ingest.p99Nanos() / 1_000_000.0);
    }

    public Optional<WDCChunkDump> dumpChunk(String dimensionId, int chunkX, int chunkZ) {
        WDCCache.EntryView view = view(dimensionId, chunkX, chunkZ);
        if (view == null) return Optional.empty();
        WDCChunk chunk = view.chunk();
        List<WDCSection> ordered = new ArrayList<>(chunk.sections());
        ordered.sort(Comparator.comparingInt(WDCSection::sectionY));
        List<WDCSectionDump> sections = new ArrayList<>(ordered.size());
        for (WDCSection section : ordered) sections.add(describe(section));
        return Optional.of(new WDCChunkDump(dimensionId, chunkX, chunkZ, freshnessOf(view.freshness()), chunk.version(),
                chunk.estimatedBytes(), chunk.biomePresent(), chunk.biomePresent() ? chunk.biomeId() : null, sections));
    }

    public Optional<WDCSectionDump> dumpSection(String dimensionId, int chunkX, int sectionY, int chunkZ) {
        WDCCache.EntryView view = view(dimensionId, chunkX, chunkZ);
        if (view == null) return Optional.empty();
        WDCSection section = view.chunk().getSection(sectionY);
        return section == null ? Optional.empty() : Optional.of(describe(section));
    }

    private WDCCache.EntryView view(String dimensionId, int chunkX, int chunkZ) {
        if (cache == null) return null;
        if (dimensionId == null || dimensionId.isBlank()) throw new IllegalArgumentException("dimensionId must not be blank");
        return cache.inspect(cache.currentWorldKey(dimensionId), chunkX, chunkZ);
    }

    private WDCSectionDump describe(WDCSection section) {
        List<WDCSection.StateCount> states = new ArrayList<>(section.stateCounts());
        states.sort(Comparator.comparingInt(WDCSection.StateCount::count).reversed()
                .thenComparingInt(WDCSection.StateCount::globalStateId));
        List<WDCPaletteEntry> palette = new ArrayList<>(states.size());
        for (WDCSection.StateCount state : states) {
            String blockId = "unknown";
            String text = "unknown";
            if (registry != null) {
                var key = registry.keyOf(state.globalStateId());
                blockId = key.blockId();
                text = key.asString();
            }
            palette.add(new WDCPaletteEntry(state.globalStateId(), blockId, text, state.count()));
        }
        return new WDCSectionDump(section.sectionY(), section.mode().name(), section.occupiedCount(),
                section.paletteSize(), section.version(), section.estimatedBytes(), List.copyOf(palette));
    }

    private long[] scan() {
        long[] totals = new long[2];
        if (cache == null) return totals;
        cache.forEachEntry((dimensionId, key, freshness, chunk) -> {
            totals[0] += chunk.estimatedBytes();
            totals[1] += chunk.sectionCount();
        });
        return totals;
    }

    private static WDCFreshness freshnessOf(DataFreshness freshness) {
        return switch (freshness) {
            case LIVE -> WDCFreshness.LIVE;
            case STALE -> WDCFreshness.STALE;
            case UNAVAILABLE -> WDCFreshness.UNAVAILABLE;
        };
    }
}
