package net.wdc.core.world.governor;

import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.ingestion.MinecraftChunkView;
import net.wdc.core.world.storage.ChunkKey;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

final class GovernedIngestionQueue {

    private static final long AGING_THRESHOLD_TICKS = 40L;

    private volatile int capacity;
    private volatile int deferredMax = 0;
    private final Map<GovernorPriority, ArrayDeque<Entry>> tiers = new EnumMap<>(GovernorPriority.class);
    private final Map<QueueKey, Entry> index = new HashMap<>();
    private final GovernorPriority[] drainOrder;

    private long enqueueClock = 0L;
    private long gameTick = 0L;
    private int size = 0;
    private int maxDepthObserved = 0;
    private int deferredInQueue = 0;

    GovernedIngestionQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
        for (GovernorPriority priority : GovernorPriority.values()) tiers.put(priority, new ArrayDeque<>());
        GovernorPriority[] order = GovernorPriority.values();
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(a.tier(), b.tier()));
        this.drainOrder = order;
    }

    record QueueKey(WorldKey world, ChunkKey chunk) {}

    static final class Entry {
        final QueueKey key;
        MinecraftChunkView chunkView;
        GovernorPriority priority;
        final long enqueuedAtTick;
        long offeredGameTick;
        boolean deferredCounted = false;
        boolean force = false;

        Entry(QueueKey key, MinecraftChunkView chunkView, GovernorPriority priority, long enqueuedAtTick) {
            this.key = key; this.chunkView = chunkView; this.priority = priority; this.enqueuedAtTick = enqueuedAtTick;
        }
    }

    synchronized boolean offer(WorldKey world, ChunkKey chunk, MinecraftChunkView chunkView, GovernorPriority priority) {
        return offer(world, chunk, chunkView, priority, false);
    }

    synchronized boolean offer(WorldKey world, ChunkKey chunk, MinecraftChunkView chunkView, GovernorPriority priority, boolean force) {
        QueueKey key = new QueueKey(world, chunk);
        Entry existing = index.get(key);
        if (existing != null) {
            existing.chunkView = chunkView;
            existing.force |= force;
            if (priority.tier() < existing.priority.tier()) moveTier(existing, priority);
            WDCMetrics.increment(WDCMetrics.Counter.GOVERNOR_COALESCED);
            return false;
        }
        if (!force && deferredMax > 0 && deferredInQueue >= deferredMax && priority.tier() >= GovernorPriority.DISTANCE.tier()) {
            WDCMetrics.increment(WDCMetrics.Counter.GOVERNOR_DROPPED);
            return false;
        }
        if (size >= capacity && !tryEvictForIncoming(priority)) {
            WDCMetrics.increment(WDCMetrics.Counter.GOVERNOR_DROPPED);
            return false;
        }
        Entry entry = new Entry(key, chunkView, priority, enqueueClock++);
        entry.offeredGameTick = gameTick;
        entry.force = force;
        tiers.get(priority).addLast(entry);
        index.put(key, entry);
        size++;
        if (size > maxDepthObserved) maxDepthObserved = size;
        WDCMetrics.increment(WDCMetrics.Counter.GOVERNOR_ENQUEUED);
        return true;
    }

    synchronized boolean requeue(Entry entry) {
        Entry existing = index.get(entry.key);
        if (existing != null) {
            existing.force |= entry.force;
            return false;
        }
        if (size >= capacity && !tryEvictForIncoming(entry.priority)) {
            WDCMetrics.increment(WDCMetrics.Counter.GOVERNOR_DROPPED);
            return false;
        }
        tiers.get(entry.priority).addLast(entry);
        index.put(entry.key, entry);
        size++;
        if (entry.deferredCounted) deferredInQueue++;
        if (size > maxDepthObserved) maxDepthObserved = size;
        return true;
    }

    synchronized void advanceTick() {
        gameTick++;
    }

    synchronized int expireOlderThan(int timeoutTicks) {
        int expired = 0;
        for (ArrayDeque<Entry> queue : tiers.values()) {
            Iterator<Entry> iterator = queue.iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next();
                if (gameTick - entry.offeredGameTick < timeoutTicks) continue;
                iterator.remove();
                index.remove(entry.key);
                size--;
                if (entry.deferredCounted) deferredInQueue--;
                expired++;
            }
        }
        if (expired > 0) WDCMetrics.add(WDCMetrics.Counter.GOVERNOR_TIMED_OUT, expired);
        return expired;
    }

    synchronized int resize(int newCapacity) {
        if (newCapacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = newCapacity;
        int dropped = 0;
        while (size > capacity && evictWorst()) dropped++;
        return dropped;
    }

    private boolean evictWorst() {
        for (int i = drainOrder.length - 1; i >= 0; i--) {
            Iterator<Entry> it = tiers.get(drainOrder[i]).descendingIterator();
            while (it.hasNext()) {
                Entry candidate = it.next();
                if (!candidate.force) {
                    it.remove();
                    discard(candidate);
                    return true;
                }
            }
        }
        for (int i = drainOrder.length - 1; i >= 0; i--) {
            Entry victim = tiers.get(drainOrder[i]).pollLast();
            if (victim != null) {
                discard(victim);
                return true;
            }
        }
        return false;
    }

    private void discard(Entry victim) {
        index.remove(victim.key);
        size--;
        if (victim.deferredCounted) deferredInQueue--;
        WDCMetrics.increment(WDCMetrics.Counter.GOVERNOR_DROPPED);
    }

    private boolean tryEvictForIncoming(GovernorPriority incoming) {
        for (int i = drainOrder.length - 1; i >= 0; i--) {
            GovernorPriority tier = drainOrder[i];
            if (tier.tier() <= incoming.tier()) break;
            Entry victim = tiers.get(tier).pollLast();
            if (victim != null) {
                discard(victim);
                return true;
            }
        }
        return false;
    }

    private void moveTier(Entry entry, GovernorPriority newPriority) {
        tiers.get(entry.priority).remove(entry);
        entry.priority = newPriority;
        tiers.get(newPriority).addLast(entry);
    }

    synchronized Entry poll() {
        promoteAgedEntries();
        for (GovernorPriority priority : drainOrder) {
            Entry head = tiers.get(priority).poll();
            if (head != null) {
                index.remove(head.key);
                size--;
                if (head.deferredCounted) deferredInQueue--;
                return head;
            }
        }
        return null;
    }

    private void promoteAgedEntries() {
        for (int i = drainOrder.length - 1; i > 0; i--) {
            ArrayDeque<Entry> queue = tiers.get(drainOrder[i]);
            int scanned = 0, count = queue.size();
            while (scanned < count) {
                Entry entry = queue.peekFirst();
                if (entry == null || enqueueClock - entry.enqueuedAtTick < AGING_THRESHOLD_TICKS) break;
                queue.pollFirst();
                GovernorPriority promoted = drainOrder[i - 1];
                tiers.get(promoted).addLast(entry);
                entry.priority = promoted;
                scanned++;
            }
        }
    }

    synchronized long markDeferredAndCount() {
        long newly = 0;
        for (ArrayDeque<Entry> tier : tiers.values()) {
            for (Entry e : tier) {
                if (!e.deferredCounted) { e.deferredCounted = true; newly++; }
            }
        }
        deferredInQueue += (int) newly;
        return newly;
    }

    synchronized boolean contains(WorldKey world, ChunkKey chunk) {
        return index.containsKey(new QueueKey(world, chunk));
    }

    synchronized void removeAllForWorld(WorldKey world) {
        for (ArrayDeque<Entry> queue : tiers.values()) queue.removeIf(entry -> entry.key.world().equals(world));
        index.keySet().removeIf(k -> k.world().equals(world));
        size = index.size();
        int deferred = 0;
        for (Entry e : index.values()) if (e.deferredCounted) deferred++;
        deferredInQueue = deferred;
    }

    synchronized boolean remove(WorldKey world, ChunkKey chunk) {
        Entry entry = index.remove(new QueueKey(world, chunk));
        if (entry == null) return false;
        tiers.get(entry.priority).remove(entry);
        size--;
        if (entry.deferredCounted) deferredInQueue--;
        return true;
    }

    synchronized void clear() {
        for (ArrayDeque<Entry> queue : tiers.values()) queue.clear();
        index.clear();
        size = 0;
        deferredInQueue = 0;
    }

    void setDeferredMax(int value) { this.deferredMax = Math.max(0, value); }
    int deferredMax() { return deferredMax; }
    synchronized int deferredInQueue() { return deferredInQueue; }
    synchronized int size() { return size; }
    synchronized int maxDepthObserved() { return maxDepthObserved; }
    int capacity() { return capacity; }
}
