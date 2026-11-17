package net.wdc.core.debug;

public final class CoreCpuMeter {

    public static final int WINDOW_TICKS = 20;
    private static final long MAX_PLAUSIBLE_TICK_NANOS = 5_000_000_000L;

    public record Snapshot(boolean measured, int ticks, double millisPerTick, double percentOfTick, double peakMillis) {
        public static final Snapshot NONE = new Snapshot(false, 0, 0.0, 0.0, 0.0);
    }

    private final long[] coreRing = new long[WINDOW_TICKS];
    private final long[] wallRing = new long[WINDOW_TICKS];
    private int ringIndex;
    private int filled;
    private long lastBeginNanos;
    private boolean started;
    private long pendingCoreNanos;
    private volatile Snapshot snapshot = Snapshot.NONE;

    public void beginTick(long nowNanos) {
        if (started) {
            long wall = nowNanos - lastBeginNanos;
            if (wall > 0L && wall <= MAX_PLAUSIBLE_TICK_NANOS) {
                coreRing[ringIndex] = pendingCoreNanos;
                wallRing[ringIndex] = wall;
                ringIndex = (ringIndex + 1) % WINDOW_TICKS;
                if (filled < WINDOW_TICKS) filled++;
                publish();
            }
        }
        pendingCoreNanos = 0L;
        lastBeginNanos = nowNanos;
        started = true;
    }

    public void add(long nanos) {
        if (nanos > 0L) pendingCoreNanos += nanos;
    }

    public Snapshot snapshot() { return snapshot; }

    public void reset() {
        java.util.Arrays.fill(coreRing, 0L);
        java.util.Arrays.fill(wallRing, 0L);
        ringIndex = 0;
        filled = 0;
        lastBeginNanos = 0L;
        started = false;
        pendingCoreNanos = 0L;
        snapshot = Snapshot.NONE;
    }

    private void publish() {
        long core = 0L;
        long wall = 0L;
        long peak = 0L;
        for (int i = 0; i < filled; i++) {
            core += coreRing[i];
            wall += wallRing[i];
            peak = Math.max(peak, coreRing[i]);
        }
        if (wall <= 0L) return;
        snapshot = new Snapshot(true, filled, core / (double) filled / 1_000_000.0, core * 100.0 / wall, peak / 1_000_000.0);
    }
}
