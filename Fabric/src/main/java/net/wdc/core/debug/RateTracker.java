package net.wdc.core.debug;

import java.util.concurrent.atomic.AtomicLongArray;

public final class RateTracker {

    private static final int BUCKET_COUNT = 10;
    private static final long BUCKET_SPAN_NANOS = 100_000_000L;

    private final AtomicLongArray bucketCounts = new AtomicLongArray(BUCKET_COUNT);
    private final AtomicLongArray bucketPeriods = new AtomicLongArray(BUCKET_COUNT);

    public void recordEvent() {
        recordEvents(1L);
    }

    public void recordEvents(long count) {
        long period = System.nanoTime() / BUCKET_SPAN_NANOS;
        int index = (int) (period % BUCKET_COUNT);
        if (bucketPeriods.getAndSet(index, period) != period) {
            bucketCounts.set(index, 0L);
        }
        bucketCounts.addAndGet(index, count);
    }

    public double ratePerSecond() {
        long currentPeriod = System.nanoTime() / BUCKET_SPAN_NANOS;
        long total = 0L;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            long age = currentPeriod - bucketPeriods.get(i);
            if (age >= 0 && age < BUCKET_COUNT) {
                total += bucketCounts.get(i);
            }
        }
        return total;
    }
}
