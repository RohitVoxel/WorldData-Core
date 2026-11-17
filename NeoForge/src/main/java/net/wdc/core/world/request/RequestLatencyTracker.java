package net.wdc.core.world.request;

import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.debug.WDCOps;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RequestLatencyTracker implements RequestMetricsListener {

    private static final class Entry {
        final long submittedAtNanos;
        volatile long lastResultAtNanos;
        volatile boolean firstResultRecorded = false;

        Entry(long now) {
            this.submittedAtNanos = now;
            this.lastResultAtNanos = now;
        }
    }

    private final Map<RequestId, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public void onSubmitted(RequestId id) {
        if (id == null) return;
        entries.put(id, new Entry(System.nanoTime()));
        WDCMetrics.increment(WDCMetrics.Counter.REQUESTS_SUBMITTED);
    }

    public void onResultDelivered(RequestId id) {
        Entry entry = (id == null) ? null : entries.get(id);
        if (entry == null) return;
        long now = System.nanoTime();
        if (!entry.firstResultRecorded) {
            entry.firstResultRecorded = true;
            WDCMetrics.record(WDCMetrics.Timer.TIME_TO_FIRST_RESULT, now - entry.submittedAtNanos);
            WDCMetrics.increment(WDCMetrics.Counter.PROGRESSIVE_FIRST_RESULTS);
        } else {
            WDCMetrics.record(WDCMetrics.Timer.TIME_TO_NEXT_RESULT, now - entry.lastResultAtNanos);
            WDCMetrics.increment(WDCMetrics.Counter.PROGRESSIVE_NEXT_RESULTS);
        }
        entry.lastResultAtNanos = now;
        WDCMetrics.increment(WDCMetrics.Counter.PROGRESSIVE_PARTIAL_RESULTS);
    }

    @Override
    public void onCompleted(RequestId id) {
        Entry entry = (id == null) ? null : entries.remove(id);
        if (entry == null) return;
        WDCMetrics.record(WDCMetrics.Timer.TIME_TO_COMPLETE, System.nanoTime() - entry.submittedAtNanos);
        WDCMetrics.increment(WDCMetrics.Counter.REQUESTS_COMPLETED);
        WDCMetrics.mark(WDCMetrics.Rate.REQUESTS_COMPLETED);
        WDCOps.record(WDCOps.Kind.REQUEST);
    }

    @Override
    public void onTerminated(RequestId id, RequestLifecycleState terminalState) {
        if (id == null) return;
        entries.remove(id);
        switch (terminalState) {
            case CANCELLED -> WDCMetrics.increment(WDCMetrics.Counter.REQUESTS_CANCELED);
            case EXPIRED -> WDCMetrics.increment(WDCMetrics.Counter.REQUESTS_EXPIRED);
            case FAILED -> WDCMetrics.increment(WDCMetrics.Counter.REQUESTS_FAILED);
            default -> { }
        }
    }

    public int trackedCount() { return entries.size(); }

    public void reset() { entries.clear(); }
}
