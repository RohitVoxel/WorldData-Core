package net.wdc.core.world.demand;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class AdaptivePriorityScheduler<T> {
    private static final QueryPriority[] DRAIN_ORDER = drainOrder();

    private final Map<QueryPriority, ArrayDeque<T>> queues = new EnumMap<>(QueryPriority.class);
    private final Set<T> queued = new HashSet<>();

    public AdaptivePriorityScheduler() {
        for (QueryPriority priority : QueryPriority.values()) {
            queues.put(priority, new ArrayDeque<>());
        }
    }

    private static QueryPriority[] drainOrder() {
        QueryPriority[] order = QueryPriority.values();
        Arrays.sort(order, (a, b) -> Integer.compare(a.schedulerTier(), b.schedulerTier()));
        return order;
    }

    public synchronized boolean enqueue(T item, QueryPriority priority) {
        if (item == null || priority == null) throw new IllegalArgumentException("item/priority must not be null");
        if (!queued.add(item)) return false;
        queues.get(priority).addLast(item);
        return true;
    }

    public synchronized boolean isQueued(T item) {
        return queued.contains(item);
    }

    public synchronized int pendingCount() {
        return queued.size();
    }

    public synchronized void clear() {
        for (ArrayDeque<T> queue : queues.values()) queue.clear();
        queued.clear();
    }

    public int drainWithin(long maxNanos, Consumer<T> consumer) {
        if (consumer == null) throw new IllegalArgumentException("consumer required");
        long start = System.nanoTime();
        int processed = 0;
        while (System.nanoTime() - start < maxNanos) {
            T item = pollNext();
            if (item == null) break;
            consumer.accept(item);
            processed++;
        }
        return processed;
    }

    private synchronized T pollNext() {
        for (QueryPriority priority : DRAIN_ORDER) {
            ArrayDeque<T> queue = queues.get(priority);
            T head = queue.poll();
            if (head != null) {
                queued.remove(head);
                return head;
            }
        }
        return null;
    }
}
