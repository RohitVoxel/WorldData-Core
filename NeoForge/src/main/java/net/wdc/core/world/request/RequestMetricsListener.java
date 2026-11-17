package net.wdc.core.world.request;

public interface RequestMetricsListener {

    RequestMetricsListener NO_OP = new RequestMetricsListener() {
        @Override public void onSubmitted(RequestId id) {}
        @Override public void onCompleted(RequestId id) {}
        @Override public void onTerminated(RequestId id, RequestLifecycleState terminalState) {}
    };

    void onSubmitted(RequestId id);

    void onCompleted(RequestId id);

    void onTerminated(RequestId id, RequestLifecycleState terminalState);
}
