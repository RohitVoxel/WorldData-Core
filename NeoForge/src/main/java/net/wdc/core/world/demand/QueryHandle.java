package net.wdc.core.world.demand;

import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.storage.ChunkKey;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class QueryHandle {
    public enum State { ACTIVE, COMPLETE, CANCELLED }

    private final WDCDemandService service;
    private final WorldKey world;
    private final QueryScope scope;
    private final int maxActiveChunks;
    private final Map<ChunkKey, ChunkDemand> active = new LinkedHashMap<>();
    private State state = State.ACTIVE;

    QueryHandle(WDCDemandService service, WorldKey world, QueryScope scope, int maxActiveChunks) {
        if (service == null || world == null || scope == null) throw new IllegalArgumentException("args must not be null");
        if (maxActiveChunks < 1) throw new IllegalArgumentException("maxActiveChunks must be >= 1");
        this.service = service;
        this.world = world;
        this.scope = scope;
        this.maxActiveChunks = maxActiveChunks;
    }

    public QueryScope scope() { return scope; }
    public WorldKey world() { return world; }

    public synchronized DemandResult request(int chunkX, int chunkZ, Set<DataLayer> layers,
                                             QueryPriority priority, RetentionPolicy retention) {
        if (state != State.ACTIVE) throw new IllegalStateException("query handle is " + state);
        if (layers == null || layers.isEmpty()) throw new IllegalArgumentException("layers must not be null or empty");
        if (priority == null) throw new IllegalArgumentException("priority must not be null");
        if (retention == null) throw new IllegalArgumentException("retention must not be null");

        ChunkKey key = new ChunkKey(chunkX, chunkZ);
        ChunkDemand existing = active.get(key);
        if (existing == null) {
            if (active.size() >= maxActiveChunks) {
                throw new IllegalStateException("query window is full");
            }
            ChunkDemand demand = ChunkDemand.of(chunkX, chunkZ, layers, priority, retention);
            active.put(key, demand);
            service.addDemand(this, world, key, layers, priority);
            service.scheduleAcquire(world, key, priority);
        } else {
            Set<DataLayer> merged = new LinkedHashSet<>(existing.layers());
            merged.addAll(layers);
            ChunkDemand demand = ChunkDemand.of(chunkX, chunkZ, merged, priority, retention);
            active.put(key, demand);
            service.addDemand(this, world, key, merged, priority);
        }
        return service.query(world, key, layers);
    }

    public synchronized void release(int chunkX, int chunkZ) {
        if (state != State.ACTIVE) return;
        ChunkKey key = new ChunkKey(chunkX, chunkZ);
        if (active.remove(key) != null) {
            service.releaseDemand(this, world, key);
        }
    }

    public synchronized Set<ChunkKey> activeChunks() { return Set.copyOf(active.keySet()); }
    public synchronized int activeCount() { return active.size(); }
    public synchronized boolean contains(ChunkKey chunk) { return active.containsKey(chunk); }
    public synchronized State state() { return state; }

    public synchronized void complete() { finish(State.COMPLETE); }
    public synchronized void cancel() { finish(State.CANCELLED); }

    private void finish(State end) {
        if (state != State.ACTIVE) return;
        for (ChunkKey key : active.keySet()) {
            service.releaseDemand(this, world, key);
        }
        active.clear();
        state = end;
        service.forgetQuery(this);
    }
}
