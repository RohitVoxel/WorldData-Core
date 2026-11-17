package net.wdc.core.world.request;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum RequestLifecycleState {
    QUEUED,
    ACQUIRING,
    PARTIAL,
    COMPLETE,
    CANCELLED,
    EXPIRED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETE || this == CANCELLED || this == EXPIRED || this == FAILED;
    }

    private static final Map<RequestLifecycleState, Set<RequestLifecycleState>> ALLOWED = Map.of(
            QUEUED, EnumSet.of(ACQUIRING, CANCELLED, EXPIRED, FAILED),
            ACQUIRING, EnumSet.of(PARTIAL, COMPLETE, CANCELLED, EXPIRED, FAILED),
            PARTIAL, EnumSet.of(ACQUIRING, PARTIAL, COMPLETE, CANCELLED, EXPIRED, FAILED),
            COMPLETE, EnumSet.noneOf(RequestLifecycleState.class),
            CANCELLED, EnumSet.noneOf(RequestLifecycleState.class),
            EXPIRED, EnumSet.noneOf(RequestLifecycleState.class),
            FAILED, EnumSet.noneOf(RequestLifecycleState.class)
    );

    public boolean canTransitionTo(RequestLifecycleState next) {
        if (next == null) return false;
        return ALLOWED.get(this).contains(next);
    }
}
