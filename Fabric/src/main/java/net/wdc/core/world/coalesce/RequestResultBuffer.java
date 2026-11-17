package net.wdc.core.world.coalesce;

import net.wdc.core.debug.WDCMetrics;

import java.util.ArrayDeque;
import net.wdc.core.world.storage.ChunkKey;

import java.util.ArrayList;
import java.util.List;

final class RequestResultBuffer {

    private final int capacity;
    private final ArrayDeque<ChunkResult> buffer;

    RequestResultBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    synchronized void offer(ChunkResult result) {
        if (buffer.size() >= capacity) {
            buffer.pollFirst();
            WDCMetrics.increment(WDCMetrics.Counter.COALESCE_RESULTS_DROPPED_BUFFER_FULL);
        }
        buffer.addLast(result);
    }

    synchronized List<ChunkResult> poll(int maxResults) {
        if (maxResults < 1) throw new IllegalArgumentException("maxResults must be >= 1");
        List<ChunkResult> out = new ArrayList<>(Math.min(maxResults, buffer.size()));
        while (out.size() < maxResults) {
            ChunkResult next = buffer.pollFirst();
            if (next == null) break;
            out.add(next);
        }
        return out;
    }

    synchronized int depth() { return buffer.size(); }

    int maxCapacity() { return capacity; }

    synchronized void remove(ChunkKey chunk) { buffer.removeIf(result -> result.chunk().equals(chunk)); }

    synchronized void clear() { buffer.clear(); }
}
