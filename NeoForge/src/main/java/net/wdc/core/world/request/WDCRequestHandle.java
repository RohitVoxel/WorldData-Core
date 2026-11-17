package net.wdc.core.world.request;

public final class WDCRequestHandle {

    private final RequestManager manager;
    private final RequestRecord record;

    WDCRequestHandle(RequestManager manager, RequestRecord record) {
        if (manager == null || record == null) throw new IllegalArgumentException("args must not be null");
        this.manager = manager;
        this.record = record;
    }

    public RequestId id() { return record.id(); }
    public RequestGeneration generation() { return record.generation(); }
    public RequestLifecycleState state() { return record.state(); }
    public RequestProgress progress() { return record.progress(); }
    public WDCRequest request() { return record.request(); }

    public boolean isActive() { return record.isActive(); }
    public boolean isCancelled() { return record.isCancelled(); }
    public boolean isExpired() { return record.isExpired(); }
    public boolean isComplete() { return record.state() == RequestLifecycleState.COMPLETE; }
    public boolean isFailed() { return record.state() == RequestLifecycleState.FAILED; }

    public void cancel() { manager.cancel(this); }

    RequestRecord recordRef() { return record; }
}
