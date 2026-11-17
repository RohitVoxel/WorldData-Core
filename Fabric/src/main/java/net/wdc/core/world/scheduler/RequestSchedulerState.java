package net.wdc.core.world.scheduler;

import net.wdc.core.world.request.RequestGeneration;
import net.wdc.core.world.request.RequestId;
import net.wdc.core.world.request.RequestRecord;
import net.wdc.core.world.storage.ChunkKey;

import java.util.LinkedHashSet;
import java.util.Set;

final class RequestSchedulerState {

    final RequestId requestId;
    final RequestGeneration generation;
    final RequestRecord record;
    final RequestFrontier frontier;

    private final Set<ChunkKey> activeChunks = new LinkedHashSet<>();

    private long lastServicedTick;
    private long enteredCurrentTierAtTick;
    private SchedulerPriority currentTier;

    RequestSchedulerState(RequestRecord record, RequestFrontier frontier, long nowTick, SchedulerPriority initialTier) {
        if (record == null || frontier == null || initialTier == null) throw new IllegalArgumentException("args must not be null");
        this.requestId = record.id();
        this.generation = record.generation();
        this.record = record;
        this.frontier = frontier;
        this.lastServicedTick = nowTick;
        this.enteredCurrentTierAtTick = nowTick;
        this.currentTier = initialTier;
    }

    boolean tryActivate(ChunkKey chunk) { return activeChunks.add(chunk); }

    void release(ChunkKey chunk) { activeChunks.remove(chunk); }

    boolean isActive(ChunkKey chunk) { return activeChunks.contains(chunk); }

    int activeCount() { return activeChunks.size(); }

    Set<ChunkKey> activeChunksView() { return Set.copyOf(activeChunks); }

    void markServiced(long nowTick) { lastServicedTick = nowTick; }

    long ticksSinceServiced(long nowTick) { return nowTick - lastServicedTick; }

    SchedulerPriority currentTier() { return currentTier; }

    long ticksInCurrentTier(long nowTick) { return nowTick - enteredCurrentTierAtTick; }

    void raiseTier(SchedulerPriority tier, long nowTick) {
        if (tier.tier() < currentTier.tier()) {
            currentTier = tier;
            enteredCurrentTierAtTick = nowTick;
        }
    }

    void resetTier(SchedulerPriority tier, long nowTick) {
        currentTier = tier;
        enteredCurrentTierAtTick = nowTick;
    }
}
