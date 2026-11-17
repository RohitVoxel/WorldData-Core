package net.wdc.core.debug;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

public final class WDCMetrics {

    private WDCMetrics() {}

    public enum Counter {
        CHUNK_INGESTIONS,
        BUDGET_PHASE_SKIPPED,
        BUDGET_PHASE_OVERRUN,
        MC_HELD_NOT_IN_CORE,
        EMPTY_SECTIONS_SKIPPED,
        NON_EMPTY_SECTIONS_PROCESSED,
        SECTIONS_FULLY_SCANNED,
        CACHE_LIVE_HITS,
        CACHE_STALE_VALIDATIONS,
        CACHE_REBUILDS,
        CACHE_EVICTIONS,
        CACHE_LIVE_RELEASED,
        LIVE_RECONCILED,
        STALE_REVIVED,
        RESYNC_OFFERED,

        GOVERNOR_ENQUEUED,
        GOVERNOR_COALESCED,
        GOVERNOR_DROPPED,
        GOVERNOR_DEFERRED,
        GOVERNOR_COMPLETED,
        GOVERNOR_BUDGET_EXHAUSTED,
        GOVERNOR_LIVE_SKIPPED,
        GOVERNOR_STALE_REVALIDATED,
        GOVERNOR_LIVE_LIMIT_SKIPPED,
        GOVERNOR_LIVE_WAITING,
        GOVERNOR_TIMED_OUT,
        GOVERNOR_LIVE_MADE_ROOM,
        BRIDGE_PULL_DRAINS,
        BRIDGE_PULL_RESYNCS,

        COALESCE_WORK_CREATED,
        COALESCE_WORK_SHARED,
        COALESCE_REQUESTS_COALESCED,
        MULTIPLAYER_SHARED_CANONICAL_WORK,
        COALESCE_DUPLICATE_WORK_PREVENTED,
        MULTIPLAYER_DUPLICATE_PLAYER_WORK_PREVENTED,
        COALESCE_WORK_LIVE_FAST_PATH,
        COALESCE_WORK_SUBMITTED_TO_DEMAND,
        COALESCE_WORK_STALE_REVALIDATING,
        COALESCE_DUPLICATE_COMPLETION_PREVENTED,
        COALESCE_RESULTS_DELIVERED,
        COALESCE_WORK_OBSOLETE,
        COALESCE_RESULTS_POLLED,
        COALESCE_RESULTS_REJECTED_STALE_GENERATION,
        COALESCE_INTEREST_CANCELED,
        COALESCE_INTEREST_EXPIRED,
        COALESCE_RESULTS_DROPPED_BUFFER_FULL,

        REQUESTS_SUBMITTED,
        REQUESTS_COMPLETED,
        REQUESTS_CANCELED,
        REQUESTS_EXPIRED,
        REQUESTS_FAILED,

        PROGRESSIVE_FIRST_RESULTS,
        PROGRESSIVE_NEXT_RESULTS,
        PROGRESSIVE_PARTIAL_RESULTS,

        REQUEST_SCHEDULER_WORK_SELECTED,
        REQUEST_SCHEDULER_WORK_CANCELED,
        REQUEST_SCHEDULER_WORK_EXPIRED,
        REQUEST_SCHEDULER_WORK_COMPLETED,
        REQUEST_SCHEDULER_AGING_PROMOTIONS,
        REQUEST_SCHEDULER_FRONTIER_STALLS,
        REQUEST_SCHEDULER_DUPLICATE_SUPPRESSED,
        REQUEST_SCHEDULER_WORK_DEFERRED,
        REQUEST_SCHEDULER_FRONTIER_EXPANSIONS,
        REQUEST_SCHEDULER_WORK_DEMOTED,

        SUBMIT_TIME_LIVE_HITS,
        DEMAND_STALE_REUSED,
        DEMAND_STALE_REBUILT,

        DERIVED_LAYER_REUSED,
        DERIVED_LAYER_JOINED,
        DERIVED_LAYER_BUILT,

        CHANGE_BATCHES_DISPATCHED,
        CHANGE_EVENTS_RECORDED,
        CHANGE_BATCHES_TRUNCATED,
        CHANGE_LISTENER_FAILURES,
        CHANGE_DIRTY_REQUESTED,
        CHANGE_DIRTY_REREAD,
        CHANGE_CHUNKS_REBUILT,
        CHANGE_REBUILD_DEFERRED,

        LIGHT_READ_HIT,
        LIGHT_READ_PENDING,
        LIGHT_READ_STALE,
        LIGHT_READ_MISSING_SECTION,
        LIGHT_READ_NO_CLOCK,
        LIGHT_REQUEST_DROPPED,
        LIGHT_SECTION_BUILT,
        LIGHT_SECTION_UNLOADED,
        LIGHT_BUILD_FAST,
        LIGHT_BUILD_SLOW,
        LIGHT_FASTPATH_MISMATCH,

        INGEST_SECTIONS_RAW,
        INGEST_SECTIONS_COMPATIBLE
    }

    public enum Timer {
        INGESTION,
        PHASE_SCHEDULER,
        PHASE_RESCAN,
        PHASE_FLUSH,
        PHASE_MEMORY,
        PHASE_MAINTENANCE,
        SNAPSHOT_CONVERSION,
        SECTION_BUILD,
        FINGERPRINT,
        CACHE_PUBLICATION,
        DEMAND_PROCESSING,
        SCHEDULER_PROCESSING,
        SPILL_READ,
        SPILL_WRITE,
        SPILL_PRUNE,
        GOVERNOR_DRAIN,

        COALESCE_SUBMIT,
        COALESCE_POLL,

        REQUEST_SCHEDULER_DECISION,

        WDC_WAIT,

        MINECRAFT_WAIT,

        TIME_TO_FIRST_RESULT,
        TIME_TO_NEXT_RESULT,
        TIME_TO_COMPLETE
    }

    public enum Rate {
        CHUNK_INGESTIONS,
        REQUESTS_COMPLETED
    }

    private static final LongAdder[] COUNTERS = newCounters();
    private static final Map<Timer, TimerStat> TIMERS = new EnumMap<>(Timer.class);
    private static final Map<Rate, RateTracker> RATES = new EnumMap<>(Rate.class);

    static {
        for (Timer t : Timer.values()) TIMERS.put(t, new TimerStat());
        for (Rate r : Rate.values()) RATES.put(r, new RateTracker());
    }

    private static LongAdder[] newCounters() {
        LongAdder[] adders = new LongAdder[Counter.values().length];
        for (int i = 0; i < adders.length; i++) adders[i] = new LongAdder();
        return adders;
    }

    public static void increment(Counter counter) { COUNTERS[counter.ordinal()].increment(); }

    public static void increment(Counter counter, long amount) { if (amount > 0L) COUNTERS[counter.ordinal()].add(amount); }
    public static void add(Counter counter, long amount) { COUNTERS[counter.ordinal()].add(amount); }
    public static long get(Counter counter) { return COUNTERS[counter.ordinal()].sum(); }
    public static void record(Timer timer, long nanos) { if (nanos >= 0) TIMERS.get(timer).record(nanos); }
    public static void mark(Rate rate) { RATES.get(rate).recordEvent(); }
    public static double ratePerSecond(Rate rate) { return RATES.get(rate).ratePerSecond(); }
    public static long startNanos() { return System.nanoTime(); }
    public static TimerSnapshot snapshot(Timer timer) { return TIMERS.get(timer).snapshot(); }

    public static void reset() {
        for (LongAdder v : COUNTERS) v.reset();
        for (TimerStat t : TIMERS.values()) t.reset();
        for (Rate r : Rate.values()) RATES.put(r, new RateTracker());
    }

    public record TimerSnapshot(long count, long totalNanos, double averageNanos, long p95Nanos, long p99Nanos, long maxNanos) {
        static final TimerSnapshot EMPTY = new TimerSnapshot(0L, 0L, 0.0, 0L, 0L, 0L);
    }

    private static final class TimerStat {
        private static final int RING_CAPACITY = 256;

        private final AtomicLongArray ring = new AtomicLongArray(RING_CAPACITY);
        private final AtomicLong writeIndex = new AtomicLong();
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong total = new AtomicLong();
        private final AtomicLong max = new AtomicLong();

        void record(long nanos) {
            count.incrementAndGet();
            total.addAndGet(nanos);
            max.accumulateAndGet(nanos, Math::max);
            int slot = (int) (writeIndex.getAndIncrement() % RING_CAPACITY);
            ring.set(slot, nanos);
        }

        TimerSnapshot snapshot() {
            long n = count.get();
            if (n == 0) return TimerSnapshot.EMPTY;
            int filled = (int) Math.min(n, RING_CAPACITY);
            long[] samples = new long[filled];
            for (int i = 0; i < filled; i++) samples[i] = ring.get(i);
            java.util.Arrays.sort(samples);
            return new TimerSnapshot(n, total.get(), total.get() / (double) n,
                    percentile(samples, 0.95), percentile(samples, 0.99), max.get());
        }

        private static long percentile(long[] sorted, double p) {
            if (sorted.length == 0) return 0L;
            int idx = Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1));
            return sorted[idx];
        }

        void reset() {
            for (int i = 0; i < RING_CAPACITY; i++) ring.set(i, 0L);
            writeIndex.set(0L); count.set(0L); total.set(0L); max.set(0L);
        }
    }
}
