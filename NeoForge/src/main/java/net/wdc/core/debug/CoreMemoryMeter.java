package net.wdc.core.debug;

import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.storage.WDCChunk;

import java.util.ArrayList;
import java.util.List;

public final class CoreMemoryMeter {

    public static final int MIN_INTERVAL_TICKS = 40;
    public static final int MAX_INTERVAL_TICKS = 400;
    private static final int ENTRIES_PER_INTERVAL_TICK = 128;

    public record Snapshot(boolean measured, long bytes, int chunks) {
        public static final Snapshot NONE = new Snapshot(false, 0L, 0);
    }

    private volatile Snapshot snapshot = Snapshot.NONE;
    private int ticksSinceSample = Integer.MAX_VALUE / 2;
    private List<WDCChunk> scan;
    private int scanCursor;
    private long scanBytes;

    public boolean sampleIfDue(WDCCache cache) {
        if (cache == null) return false;
        ticksSinceSample++;
        Snapshot current = snapshot;
        if (ticksSinceSample < intervalTicks(current.chunks())) return false;
        sample(cache);
        return true;
    }

    public void sample(WDCCache cache) {
        ticksSinceSample = 0;
        if (cache == null) {
            snapshot = Snapshot.NONE;
            return;
        }
        long[] totals = new long[2];
        cache.forEachEntry((dimensionId, key, freshness, chunk) -> {
            totals[0] += chunk.estimatedBytes() + chunk.derivedEstimatedBytes();
            totals[1]++;
        });
        snapshot = new Snapshot(true, totals[0], (int) Math.min(Integer.MAX_VALUE, totals[1]));
    }

    public boolean advanceIfDue(WDCCache cache, long budgetNanos) {
        if (cache == null) return false;
        if (scan == null) {
            ticksSinceSample++;
            Snapshot current = snapshot;
            if (ticksSinceSample < intervalTicks(current.chunks())) return false;
            List<WDCChunk> chunks = new ArrayList<>();
            cache.forEachEntry((dimensionId, key, freshness, chunk) -> chunks.add(chunk));
            scan = chunks;
            scanCursor = 0;
            scanBytes = 0L;
        }
        long start = System.nanoTime();
        List<WDCChunk> chunks = scan;
        while (scanCursor < chunks.size()) {
            WDCChunk chunk = chunks.get(scanCursor++);
            scanBytes += chunk.estimatedBytes() + chunk.derivedEstimatedBytes();
            if (System.nanoTime() - start >= budgetNanos) break;
        }
        if (scanCursor < chunks.size()) return true;
        snapshot = new Snapshot(true, scanBytes, chunks.size());
        scan = null;
        scanCursor = 0;
        scanBytes = 0L;
        ticksSinceSample = 0;
        return true;
    }

    public boolean isScanning() { return scan != null; }

    public void markDue() { ticksSinceSample = Integer.MAX_VALUE / 2; }

    public Snapshot snapshot() { return snapshot; }

    public void reset() {
        scan = null;
        scanCursor = 0;
        scanBytes = 0L;
        snapshot = Snapshot.NONE;
        ticksSinceSample = Integer.MAX_VALUE / 2;
    }

    static int intervalTicks(int chunks) {
        return Math.max(MIN_INTERVAL_TICKS, Math.min(MAX_INTERVAL_TICKS, chunks / ENTRIES_PER_INTERVAL_TICK));
    }
}
