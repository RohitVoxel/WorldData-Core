package net.wdc.core.world.request;

public final class RequestRecord {

    private final RequestId id;
    private final RequestGeneration generation;
    private final WDCRequest request;

    private volatile RequestLifecycleState state = RequestLifecycleState.QUEUED;
    private volatile RequestProgress progress;

    RequestRecord(RequestId id, RequestGeneration generation, WDCRequest request) {
        if (id == null || generation == null || request == null) throw new IllegalArgumentException("args must not be null");
        this.id = id;
        this.generation = generation;
        this.request = request;
        this.progress = RequestProgress.initial(request.scope().chunkCount());
    }

    public RequestId id() { return id; }
    public RequestGeneration generation() { return generation; }
    public WDCRequest request() { return request; }
    public RequestLifecycleState state() { return state; }
    public RequestProgress progress() { return progress; }

    public RequestScope scope() { return request.scope(); }
    public RequestPriority priority() { return request.priority(); }
    public RequestAccessPattern accessPattern() { return request.accessPattern(); }
    public Integer lookahead() { return request.lookahead(); }

    public boolean isActive() {
        return state == RequestLifecycleState.QUEUED
                || state == RequestLifecycleState.ACQUIRING
                || state == RequestLifecycleState.PARTIAL;
    }

    public boolean isCancelled() { return state == RequestLifecycleState.CANCELLED; }
    public boolean isExpired() { return state == RequestLifecycleState.EXPIRED; }

    synchronized void transitionTo(RequestLifecycleState next) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("illegal request transition: " + state + " -> " + next);
        }
        this.state = next;
    }

    synchronized void updateProgress(RequestProgress progress) {
        if (progress == null) throw new IllegalArgumentException("progress must not be null");
        this.progress = progress;
    }
}
