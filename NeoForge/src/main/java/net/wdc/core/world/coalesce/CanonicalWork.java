package net.wdc.core.world.coalesce;

import net.wdc.core.world.request.RequestId;

import java.util.LinkedHashSet;
import java.util.Set;

final class CanonicalWork {

    private final CanonicalWorkKey key;
    private final Set<RequestId> interestedRequests = new LinkedHashSet<>();
    private CanonicalWorkState state = CanonicalWorkState.NEW;
    private boolean completionDelivered = false;

    CanonicalWork(CanonicalWorkKey key) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        this.key = key;
    }

    CanonicalWorkKey key() { return key; }
    CanonicalWorkState state() { return state; }
    void state(CanonicalWorkState state) { this.state = state; }

    boolean markCompletionDelivered() {
        if (completionDelivered) return false;
        completionDelivered = true;
        return true;
    }

    synchronized boolean addInterest(RequestId requestId) { return interestedRequests.add(requestId); }

    void resetForReacquire() {
        state = CanonicalWorkState.NEW;
        completionDelivered = false;
    }

    synchronized boolean removeInterest(RequestId requestId) {
        interestedRequests.remove(requestId);
        return interestedRequests.isEmpty();
    }

    synchronized Set<RequestId> interestedRequestsSnapshot() { return Set.copyOf(interestedRequests); }

    synchronized boolean hasInterest() { return !interestedRequests.isEmpty(); }

    synchronized int interestCount() { return interestedRequests.size(); }
}
