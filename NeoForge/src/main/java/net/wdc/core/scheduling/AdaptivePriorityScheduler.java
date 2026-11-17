package net.wdc.core.scheduling;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.TreeMap;
import java.util.function.Consumer;

public final class AdaptivePriorityScheduler<T> {

    private static final double EMA_SMOOTHING = 0.2;

    private final Object lock = new Object();
    private final TreeMap<Integer, Deque<T>> tiers = new TreeMap<>();

    private long totalSubmitted = 0L;
    private long totalProcessed = 0L;
    private long lastDrainNanos = 0L;
    private int  lastDrainCount = 0;
    private double averageItemNanos = 0.0;

    public void submit(T item, int priority) {
        if (item == null) throw new IllegalArgumentException("item must not be null");
        synchronized (lock) {
            tiers.computeIfAbsent(priority, p -> new ArrayDeque<>()).add(item);
            totalSubmitted++;
        }
    }

    public int pendingCount() {
        synchronized (lock) {
            int total = 0;
            for (Deque<T> tier : tiers.values()) total += tier.size();
            return total;
        }
    }

    public int drainBudgeted(int maxItems, long maxNanos, Consumer<T> consumer) {
        if (consumer == null) throw new IllegalArgumentException("consumer must not be null");

        long start = System.nanoTime();
        int processed = 0;

        while (processed < maxItems) {
            if (processed > 0 && (System.nanoTime() - start) > maxNanos) break;

            T item = pollHighestPriority();
            if (item == null) break;

            long itemStart = System.nanoTime();
            consumer.accept(item);
            recordItemCost(System.nanoTime() - itemStart);

            processed++;
        }

        synchronized (lock) {
            lastDrainNanos = System.nanoTime() - start;
            lastDrainCount = processed;
            totalProcessed += processed;
        }

        return processed;
    }

    public int suggestItemBudget(long targetNanos) {
        synchronized (lock) {
            if (averageItemNanos <= 0.0) return Integer.MAX_VALUE;
            return Math.max(1, (int) (targetNanos / averageItemNanos));
        }
    }

    private T pollHighestPriority() {
        synchronized (lock) {
            var entry = tiers.firstEntry();
            while (entry != null && entry.getValue().isEmpty()) {
                tiers.remove(entry.getKey());
                entry = tiers.firstEntry();
            }
            if (entry == null) return null;
            T item = entry.getValue().poll();
            if (entry.getValue().isEmpty()) tiers.remove(entry.getKey());
            return item;
        }
    }

    private void recordItemCost(long itemNanos) {
        synchronized (lock) {
            averageItemNanos = (averageItemNanos <= 0.0)
                    ? itemNanos
                    : averageItemNanos + (itemNanos - averageItemNanos) * EMA_SMOOTHING;
        }
    }

    public long totalSubmitted()     { synchronized (lock) { return totalSubmitted; } }
    public long totalProcessed()     { synchronized (lock) { return totalProcessed; } }
    public long lastDrainNanos()     { synchronized (lock) { return lastDrainNanos; } }
    public int  lastDrainCount()     { synchronized (lock) { return lastDrainCount; } }
    public double averageItemNanos() { synchronized (lock) { return averageItemNanos; } }

    public void reset() {
        synchronized (lock) {
            tiers.clear();
            totalSubmitted = 0L;
            totalProcessed = 0L;
            lastDrainNanos = 0L;
            lastDrainCount = 0;
            averageItemNanos = 0.0;
        }
    }
}
