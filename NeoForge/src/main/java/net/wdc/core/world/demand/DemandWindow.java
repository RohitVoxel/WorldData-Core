package net.wdc.core.world.demand;

import net.wdc.core.world.storage.ChunkKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class DemandWindow {

    private final int maxActive;
    private final Map<ChunkKey, ChunkDemand> active = new LinkedHashMap<>();

    public DemandWindow(int maxActive) {
        if (maxActive <= 0) throw new IllegalArgumentException("maxActive must be > 0, got " + maxActive);
        this.maxActive = maxActive;
    }

    public int maxActive() {
        return maxActive;
    }

    public int size() {
        return active.size();
    }

    public boolean isFull() {
        return active.size() >= maxActive;
    }

    public boolean contains(ChunkKey chunk) {
        return active.containsKey(chunk);
    }

    public Set<ChunkKey> activeChunks() {
        return Set.copyOf(active.keySet());
    }

    boolean add(ChunkDemand demand) {
        ChunkKey chunk = demand.chunk();
        if (!active.containsKey(chunk) && isFull()) return false;
        active.put(chunk, demand);
        return true;
    }

    ChunkDemand remove(ChunkKey chunk) {
        return active.remove(chunk);
    }

    Map<ChunkKey, ChunkDemand> snapshot() {
        return Map.copyOf(active);
    }
}
