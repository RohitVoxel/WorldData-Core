package net.wdc.core.debug.monitor;

import net.wdc.core.api.request.BlockVisitor;
import net.wdc.core.config.MonitorConfig;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class WDCApiMonitor {

    public static final long REPEAT_WINDOW_NANOS = 250_000_000L;
    public static final long NO_KEY = MonitorStats.NO_KEY;

    public static volatile boolean enabled;

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final Object CONTROL = new Object();

    private static volatile MonitorStats stats = new MonitorStats(MonitorConfig.DEFAULT_MAX_CALLERS, REPEAT_WINDOW_NANOS);
    private static volatile int sampleEvery = MonitorConfig.DEFAULT_SAMPLE_EVERY;
    private static volatile boolean allowed = true;
    private static volatile Supplier<String> callerSource = CallerResolver::current;
    private static volatile LongSupplier clock = System::nanoTime;
    private static long activeNanos;
    private static long enabledAt;
    private static long resets;

    private WDCApiMonitor() {}

    public static void hit(MonitorApi api, long key) {
        hit(api, MonitorOutcome.UNKNOWN, key);
    }

    public static void hit(MonitorApi api, MonitorOutcome outcome, long key) {
        if (!enabled) return;
        if (DEPTH.get()[0] != 0) {
            stats.countNested(api);
            return;
        }
        external(api, outcome, key);
    }

    public static boolean begin(MonitorApi api, long key) {
        if (!enabled) return false;
        int[] depth = DEPTH.get();
        if (depth[0] == 0) external(api, MonitorOutcome.UNKNOWN, key);
        else stats.countNested(api);
        depth[0]++;
        return true;
    }

    public static void end(boolean began) {
        if (began) DEPTH.get()[0]--;
    }

    private static void external(MonitorApi api, MonitorOutcome outcome, long key) {
        MonitorStats current = stats;
        current.count(api, outcome);
        int every = sampleEvery;
        if (every > 1 && ThreadLocalRandom.current().nextInt(every) != 0) return;
        long started = System.nanoTime();
        String caller = callerSource.get();
        long finished = System.nanoTime();
        current.sample(caller, api, outcome, key, finished);
        current.addWalk(finished - started);
    }

    public static BlockVisitor guard(BlockVisitor visitor) {
        if (visitor == null) return null;
        return (x, y, z, stateId, blockId) -> {
            int[] depth = DEPTH.get();
            int saved = depth[0];
            depth[0] = 0;
            try {
                return visitor.visit(x, y, z, stateId, blockId);
            } finally {
                depth[0] = saved;
            }
        };
    }

    public static long posKey(int x, int y, int z) {
        return (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    public static long textKey(String text) {
        return text == null ? NO_KEY : text.hashCode() * 0x9E3779B97F4A7C15L + 1L;
    }

    public static long mixKey(long first, long second) {
        long h = first * 0x9E3779B97F4A7C15L + second;
        h ^= h >>> 31;
        return h == NO_KEY ? 0L : h;
    }

    public static boolean allowed() {
        return allowed;
    }

    public static long resets() {
        synchronized (CONTROL) {
            return resets;
        }
    }

    public static int sampleEvery() {
        return sampleEvery;
    }

    public static boolean enable() {
        synchronized (CONTROL) {
            if (!allowed) return false;
            if (!enabled) {
                enabledAt = clock.getAsLong();
                enabled = true;
            }
            return true;
        }
    }

    public static void disable() {
        synchronized (CONTROL) {
            if (enabled) {
                enabled = false;
                activeNanos += Math.max(0L, clock.getAsLong() - enabledAt);
            }
        }
    }

    public static void reset() {
        synchronized (CONTROL) {
            resets++;
            stats.reset();
            activeNanos = 0L;
            enabledAt = clock.getAsLong();
        }
    }

    public static void setSampleEvery(int every) {
        sampleEvery = Math.max(MonitorConfig.MIN_SAMPLE_EVERY, Math.min(MonitorConfig.MAX_SAMPLE_EVERY, every));
    }

    public static void applyConfig(MonitorConfig config) {
        MonitorConfig value = config == null ? new MonitorConfig() : config;
        synchronized (CONTROL) {
            allowed = value.allowed;
            setSampleEvery(value.sampleEvery);
            if (stats.maxCallers() != value.maxCallers) {
                resets++;
                stats = new MonitorStats(value.maxCallers, REPEAT_WINDOW_NANOS);
                activeNanos = 0L;
                enabledAt = clock.getAsLong();
            }
            if (!allowed && enabled) {
                enabled = false;
                activeNanos += Math.max(0L, clock.getAsLong() - enabledAt);
            }
        }
    }

    public static MonitorSnapshot snapshot(int top) {
        synchronized (CONTROL) {
            long active = activeNanos;
            if (enabled) active += Math.max(0L, clock.getAsLong() - enabledAt);
            return stats.snapshot(top, enabled, sampleEvery, active);
        }
    }

    public static MonitorStats stats() {
        return stats;
    }

    public static void installModLookup(ModLookup lookup) {
        CallerResolver.setLookup(lookup);
    }

    static void setCallerSourceForTesting(Supplier<String> source) {
        callerSource = source == null ? CallerResolver::current : source;
    }

    static void setClockForTesting(LongSupplier source) {
        clock = source == null ? System::nanoTime : source;
    }

    static void restoreDefaultsForTesting() {
        synchronized (CONTROL) {
            enabled = false;
            allowed = true;
            sampleEvery = MonitorConfig.DEFAULT_SAMPLE_EVERY;
            stats = new MonitorStats(MonitorConfig.DEFAULT_MAX_CALLERS, REPEAT_WINDOW_NANOS);
            activeNanos = 0L;
            enabledAt = 0L;
            callerSource = CallerResolver::current;
            clock = System::nanoTime;
            DEPTH.get()[0] = 0;
        }
    }
}
