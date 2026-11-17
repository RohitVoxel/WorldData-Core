package net.wdc.core.debug;

import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

public final class WDCOps {

    public enum Kind {
        INGEST, REQUEST, QUERY, DEMAND, SPILL, BRIDGE, GOVERNOR;

        public boolean external() {
            return this == REQUEST || this == QUERY;
        }
    }

    public record Line(long total, double perSecond) {}

    public record Snapshot(Line all, Line external, Line internal, Line ingest, Line request, Line query, Line demand,
                           Line spill, Line bridge, Line governor, double peakPerSecond, double peakExternalPerSecond,
                           double peakInternalPerSecond) {
        private static final Line ZERO = new Line(0L, 0.0);
        public static final Snapshot EMPTY = new Snapshot(ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO,
                0.0, 0.0, 0.0);
    }

    static final long WINDOW_NANOS = 500_000_000L;

    private static final Kind[] KINDS = Kind.values();
    private static final LongAdder[] TOTALS = newTotals();
    private static final Object LOCK = new Object();

    private static volatile LongSupplier clock = System::nanoTime;
    private static boolean windowOpen;
    private static long windowStartNanos;
    private static long[] windowStartTotals = new long[KINDS.length];
    private static final long[] TICK_TOTALS = new long[KINDS.length];
    private static double[] rates = new double[KINDS.length];
    private static double peak;
    private static double peakExternal;
    private static double peakInternal;

    private WDCOps() {}

    private static LongAdder[] newTotals() {
        LongAdder[] totals = new LongAdder[Kind.values().length];
        for (int i = 0; i < totals.length; i++) totals[i] = new LongAdder();
        return totals;
    }

    public static void record(Kind kind) { TOTALS[kind.ordinal()].increment(); }

    public static void record(Kind kind, long count) { if (count > 0L) TOTALS[kind.ordinal()].add(count); }

    public static void query() { TOTALS[Kind.QUERY.ordinal()].increment(); }

    public static long total(Kind kind) { return TOTALS[kind.ordinal()].sum(); }

    public static long total() {
        long sum = 0L;
        for (LongAdder adder : TOTALS) sum += adder.sum();
        return sum;
    }

    public static Snapshot sample() {
        return sample(clock.getAsLong());
    }

    static Snapshot sample(long nowNanos) {
        synchronized (LOCK) {
            long[] totals = new long[KINDS.length];
            for (int i = 0; i < totals.length; i++) totals[i] = TOTALS[i].sum();
            advance(nowNanos, totals);
            return build(totals);
        }
    }

    public static void tick() {
        tick(clock.getAsLong());
    }

    static void tick(long nowNanos) {
        synchronized (LOCK) {
            for (int i = 0; i < TICK_TOTALS.length; i++) TICK_TOTALS[i] = TOTALS[i].sum();
            advance(nowNanos, TICK_TOTALS);
        }
    }

    private static void advance(long nowNanos, long[] totals) {
        if (!windowOpen) {
            openWindow(nowNanos, totals);
        } else if (nowNanos - windowStartNanos >= WINDOW_NANOS) {
            double seconds = (nowNanos - windowStartNanos) / 1_000_000_000.0;
            double sum = 0.0;
            double external = 0.0;
            double internal = 0.0;
            for (int i = 0; i < totals.length; i++) {
                rates[i] = (totals[i] - windowStartTotals[i]) / seconds;
                sum += rates[i];
                if (KINDS[i].external()) external += rates[i];
                else internal += rates[i];
            }
            if (sum > peak) peak = sum;
            if (external > peakExternal) peakExternal = external;
            if (internal > peakInternal) peakInternal = internal;
            openWindow(nowNanos, totals);
        }
    }

    private static void openWindow(long nowNanos, long[] totals) {
        windowOpen = true;
        windowStartNanos = nowNanos;
        System.arraycopy(totals, 0, windowStartTotals, 0, totals.length);
    }

    private static Snapshot build(long[] totals) {
        double sumRate = 0.0;
        long sumTotal = 0L;
        double externalRate = 0.0;
        long externalTotal = 0L;
        for (int i = 0; i < totals.length; i++) {
            sumRate += rates[i];
            sumTotal += totals[i];
            if (KINDS[i].external()) {
                externalRate += rates[i];
                externalTotal += totals[i];
            }
        }
        return new Snapshot(new Line(sumTotal, sumRate),
                new Line(externalTotal, externalRate),
                new Line(sumTotal - externalTotal, sumRate - externalRate),
                line(totals, Kind.INGEST), line(totals, Kind.REQUEST), line(totals, Kind.QUERY), line(totals, Kind.DEMAND),
                line(totals, Kind.SPILL), line(totals, Kind.BRIDGE), line(totals, Kind.GOVERNOR),
                peak, peakExternal, peakInternal);
    }

    private static Line line(long[] totals, Kind kind) {
        return new Line(totals[kind.ordinal()], rates[kind.ordinal()]);
    }

    public static void resetPeak() {
        synchronized (LOCK) {
            peak = 0.0;
            peakExternal = 0.0;
            peakInternal = 0.0;
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            for (LongAdder adder : TOTALS) adder.reset();
            windowOpen = false;
            windowStartNanos = 0L;
            windowStartTotals = new long[KINDS.length];
            rates = new double[KINDS.length];
            peak = 0.0;
            peakExternal = 0.0;
            peakInternal = 0.0;
        }
    }

    static void setClock(LongSupplier source) {
        clock = source == null ? System::nanoTime : source;
    }
}
