package net.wdc.core.world.request;

import net.wdc.core.debug.WDCMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RequestManager {

    private final Map<RequestId, RequestRecord> records = new ConcurrentHashMap<>();

    private final AtomicLong idCounter = new AtomicLong();
    private final AtomicLong generationCounter = new AtomicLong();
    private volatile RequestGeneration currentGeneration = new RequestGeneration(0L);

    private volatile RequestMetricsListener metricsListener = RequestMetricsListener.NO_OP;

    public RequestGeneration currentGeneration() { return currentGeneration; }

    public void setMetricsListener(RequestMetricsListener listener) {
        this.metricsListener = (listener != null) ? listener : RequestMetricsListener.NO_OP;
    }

    public WDCRequestHandle submit(WDCRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        RequestId id = new RequestId(idCounter.incrementAndGet());
        RequestRecord record = new RequestRecord(id, currentGeneration, request);
        records.put(id, record);
        metricsListener.onSubmitted(id);
        return new WDCRequestHandle(this, record);
    }

    public void cancel(WDCRequestHandle handle) {
        if (handle == null) throw new IllegalArgumentException("handle must not be null");
        cancelRecord(handle.recordRef());
    }

    @SuppressWarnings("unused")
    public void cancel(RequestId id) {
        RequestRecord record = records.get(id);
        if (record != null) cancelRecord(record);
    }

    private void cancelRecord(RequestRecord record) {
        if (!record.state().isTerminal()) {
            record.transitionTo(RequestLifecycleState.CANCELLED);
            metricsListener.onTerminated(record.id(), RequestLifecycleState.CANCELLED);
        }
    }

    public void expire(RequestId id) {
        RequestRecord record = records.get(id);
        if (record == null) return;
        if (!record.state().isTerminal()) {
            record.transitionTo(RequestLifecycleState.EXPIRED);
            metricsListener.onTerminated(id, RequestLifecycleState.EXPIRED);
        }
    }

    public void fail(RequestId id) {
        RequestRecord record = records.get(id);
        if (record == null) return;
        if (!record.state().isTerminal()) {
            record.transitionTo(RequestLifecycleState.FAILED);
            metricsListener.onTerminated(id, RequestLifecycleState.FAILED);
        }
    }

    public void advance(RequestId id, RequestLifecycleState next) {
        RequestRecord record = records.get(id);
        if (record == null) throw new IllegalStateException("no such request: " + id);
        if (!isCurrentGeneration(record)) {
            throw new IllegalStateException("request belongs to a stale generation and cannot advance: " + id);
        }
        record.transitionTo(next);
        if (next == RequestLifecycleState.COMPLETE) metricsListener.onCompleted(id);
    }

    public void updateProgress(RequestId id, RequestProgress progress) {
        RequestRecord record = records.get(id);
        if (record == null) return;
        record.updateProgress(progress);
    }

    public RequestRecord get(RequestId id) { return records.get(id); }

    public List<RequestRecord> activeRequests() {
        List<RequestRecord> out = new ArrayList<>();
        for (RequestRecord record : records.values()) {
            if (record.isActive() && isCurrentGeneration(record)) out.add(record);
        }
        return out;
    }

    public List<RequestRecord> requestsInGeneration(RequestGeneration generation) {
        if (generation == null) throw new IllegalArgumentException("generation must not be null");
        List<RequestRecord> out = new ArrayList<>();
        for (RequestRecord record : records.values()) {
            if (record.generation().equals(generation)) out.add(record);
        }
        return out;
    }

    public boolean isCurrentGeneration(RequestRecord record) {
        return record != null && record.generation().equals(currentGeneration);
    }

    public RequestGeneration resetGeneration() {
        RequestGeneration previous = currentGeneration;
        currentGeneration = new RequestGeneration(generationCounter.incrementAndGet());
        invalidateGeneration(previous);
        return currentGeneration;
    }

    public void invalidateGeneration(RequestGeneration generation) {
        if (generation == null) throw new IllegalArgumentException("generation must not be null");
        for (RequestRecord record : records.values()) {
            if (record.generation().equals(generation) && !record.state().isTerminal()) {
                record.transitionTo(RequestLifecycleState.CANCELLED);
                metricsListener.onTerminated(record.id(), RequestLifecycleState.CANCELLED);
            }
        }
    }

    public int pruneTerminal() {
        int removed = 0;
        for (Map.Entry<RequestId, RequestRecord> entry : records.entrySet()) {
            if (entry.getValue().state().isTerminal()) {
                records.remove(entry.getKey());
                removed++;
            }
        }
        return removed;
    }

    public int trackedRequestCount() { return records.size(); }

    @SuppressWarnings("unused")
    public RequestStatusSnapshot statusSnapshot() {
        int pending = 0, active = 0;
        for (RequestRecord record : records.values()) {
            if (!isCurrentGeneration(record)) continue;
            if (record.state() == RequestLifecycleState.QUEUED) pending++;
            else if (!record.state().isTerminal()) active++;
        }
        return new RequestStatusSnapshot(active, pending,
                WDCMetrics.get(WDCMetrics.Counter.REQUESTS_COMPLETED),
                WDCMetrics.get(WDCMetrics.Counter.REQUESTS_CANCELED));
    }

    public record RequestStatusSnapshot(int active, int pending, long completed, long cancelled) {}

    public void reset() {
        records.clear();
        currentGeneration = new RequestGeneration(generationCounter.incrementAndGet());
    }
}
