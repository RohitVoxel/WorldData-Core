package net.wdc.core.debug.session;

import java.util.Locale;

public final class TickStats {

    public static final long BUDGET_NANOS = 50_000_000L;

    public record Snapshot(long samples, long minNanos, double avgNanos, long maxNanos, long overBudget) {

        public static final Snapshot EMPTY = new Snapshot(0L, 0L, 0.0, 0L, 0L);

        public double minMillis() {
            return minNanos / 1_000_000.0;
        }

        public double avgMillis() {
            return avgNanos / 1_000_000.0;
        }

        public double maxMillis() {
            return maxNanos / 1_000_000.0;
        }

        public String summary() {
            if (samples == 0L) return "no complete server tick sampled";
            return String.format(Locale.ROOT, "MSPT min %.2f avg %.2f max %.2f over %d ticks", minMillis(), avgMillis(),
                    maxMillis(), samples);
        }
    }

    private long samples;
    private long sumNanos;
    private long minNanos = Long.MAX_VALUE;
    private long maxNanos;
    private long overBudget;

    public void record(long nanos) {
        long value = Math.max(0L, nanos);
        samples++;
        sumNanos += value;
        if (value < minNanos) minNanos = value;
        if (value > maxNanos) maxNanos = value;
        if (value > BUDGET_NANOS) overBudget++;
    }

    public long samples() {
        return samples;
    }

    public void reset() {
        samples = 0L;
        sumNanos = 0L;
        minNanos = Long.MAX_VALUE;
        maxNanos = 0L;
        overBudget = 0L;
    }

    public Snapshot snapshot() {
        if (samples == 0L) return Snapshot.EMPTY;
        return new Snapshot(samples, minNanos, (double) sumNanos / samples, maxNanos, overBudget);
    }
}
