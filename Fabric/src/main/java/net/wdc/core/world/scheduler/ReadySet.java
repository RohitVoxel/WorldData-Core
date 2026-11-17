package net.wdc.core.world.scheduler;

import net.wdc.core.world.request.RequestId;
import net.wdc.core.world.storage.ChunkKey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ReadySet {

    private final Map<SchedulerPriority, ArrayDeque<ScheduledWork>> tiers = new EnumMap<>(SchedulerPriority.class);
    private final Map<WorkKey, ScheduledWork> index = new HashMap<>();
    private final SchedulerPriority[] drainOrder;

    private volatile int capacity;

    ReadySet(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
        for (SchedulerPriority priority : SchedulerPriority.values()) tiers.put(priority, new ArrayDeque<>());
        SchedulerPriority[] order = SchedulerPriority.values();
        Arrays.sort(order, (a, b) -> Integer.compare(a.tier(), b.tier()));
        this.drainOrder = order;
    }

    synchronized void setCapacity(int newCapacity) {
        if (newCapacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = newCapacity;
    }

    synchronized int remainingCapacity() { return Math.max(0, capacity - index.size()); }

    synchronized int size() { return index.size(); }

    synchronized OfferOutcome offer(ScheduledWork work) {
        if (index.containsKey(work.key())) return OfferOutcome.rejected();
        ScheduledWork evicted = null;
        if (index.size() >= capacity) {
            evicted = evictWorstTierBelow(work.priority());
            if (evicted == null) return OfferOutcome.rejected();
        }
        tiers.get(work.priority()).addLast(work);
        index.put(work.key(), work);
        return OfferOutcome.admitted(evicted);
    }

    private ScheduledWork evictWorstTierBelow(SchedulerPriority incoming) {
        for (int i = drainOrder.length - 1; i >= 0; i--) {
            SchedulerPriority tier = drainOrder[i];
            if (tier.tier() <= incoming.tier()) return null;
            ArrayDeque<ScheduledWork> bucket = tiers.get(tier);
            if (!bucket.isEmpty()) {
                ScheduledWork evicted = bucket.pollFirst();
                index.remove(evicted.key());
                return evicted;
            }
        }
        return null;
    }

    synchronized boolean removeWork(RequestId requestId, ChunkKey chunk) {
        ScheduledWork found = null;
        for (ScheduledWork w : index.values()) {
            if (w.requestId().equals(requestId) && w.chunk().equals(chunk)) { found = w; break; }
        }
        if (found == null) return false;
        return removeExact(found);
    }

    synchronized int removeAllForRequest(RequestId requestId) {
        List<ScheduledWork> toRemove = new ArrayList<>();
        for (ScheduledWork w : index.values()) {
            if (w.requestId().equals(requestId)) toRemove.add(w);
        }
        for (ScheduledWork w : toRemove) removeExact(w);
        return toRemove.size();
    }

    private boolean removeExact(ScheduledWork work) {
        ScheduledWork removed = index.remove(work.key());
        if (removed == null) return false;
        tiers.get(removed.priority()).remove(removed);
        return true;
    }

    synchronized List<ScheduledWork> snapshotOrderedByPriority() {
        List<ScheduledWork> out = new ArrayList<>(index.size());
        for (SchedulerPriority priority : drainOrder) out.addAll(tiers.get(priority));
        return out;
    }

    synchronized void clear() {
        for (ArrayDeque<ScheduledWork> tier : tiers.values()) tier.clear();
        index.clear();
    }

    record OfferOutcome(boolean admitted, ScheduledWork evicted) {
        static OfferOutcome admitted(ScheduledWork evicted) { return new OfferOutcome(true, evicted); }
        static OfferOutcome rejected() { return new OfferOutcome(false, null); }
    }
}
