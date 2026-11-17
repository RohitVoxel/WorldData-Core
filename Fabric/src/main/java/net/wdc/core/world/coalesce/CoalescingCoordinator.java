package net.wdc.core.world.coalesce;

import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.DataResult;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.demand.WDCDemandService;
import net.wdc.core.world.demand.DataLayer;
import net.wdc.core.world.demand.DemandResult;
import net.wdc.core.world.demand.LayerResult;
import net.wdc.core.world.demand.QueryPriority;
import net.wdc.core.world.demand.RetentionPolicy;
import net.wdc.core.world.request.RequestGeneration;
import net.wdc.core.world.request.RequestId;
import net.wdc.core.world.request.RequestLatencyTracker;
import net.wdc.core.world.request.RequestManager;
import net.wdc.core.world.request.RequestRecord;
import net.wdc.core.world.scheduler.RequestScheduler;
import net.wdc.core.world.scheduler.ScheduledWork;
import net.wdc.core.world.scheduler.SchedulerPriority;
import net.wdc.core.world.storage.ChunkKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CoalescingCoordinator {

    private final WDCDemandService demandService;
    private final WDCCache cache;
    private final RequestManager requestManager;
    private final RequestScheduler scheduler;
    private final CoalescingConfig config;

    private final Map<CanonicalWorkKey, CanonicalWork> canonicalWork = new ConcurrentHashMap<>();
    private final Map<RequestId, Set<CanonicalWorkKey>> interestByRequest = new ConcurrentHashMap<>();
    private final Map<RequestId, RequestResultBuffer> resultBuffers = new ConcurrentHashMap<>();
    private final Map<RequestId, Map<ChunkKey, Set<DataLayer>>> requestedLayersByChunk = new ConcurrentHashMap<>();
    private final Map<RequestId, Map<ChunkKey, Boolean>> chunkCompletionNotified = new ConcurrentHashMap<>();
    private final Map<RequestId, Set<ChunkKey>> releasedByRequest = new ConcurrentHashMap<>();

    private volatile RequestLatencyTracker latencyTracker;

    public CoalescingCoordinator(WDCDemandService demandService, WDCCache cache,
                                  RequestManager requestManager, RequestScheduler scheduler) {
        this(demandService, cache, requestManager, scheduler, new CoalescingConfig());
    }

    public CoalescingCoordinator(WDCDemandService demandService, WDCCache cache, RequestManager requestManager,
                                  RequestScheduler scheduler, CoalescingConfig config) {
        if (demandService == null || cache == null || requestManager == null || scheduler == null || config == null) {
            throw new IllegalArgumentException("demandService/cache/requestManager/scheduler/config must not be null");
        }
        this.demandService = demandService;
        this.cache = cache;
        this.requestManager = requestManager;
        this.scheduler = scheduler;
        this.config = config;
        scheduler.setBackpressureSource(this::resultBufferDepth);
    }

    public void setLatencyTracker(RequestLatencyTracker tracker) {
        this.latencyTracker = tracker;
    }

    public void submitScheduledWork(ScheduledWork work) {
        if (work == null) throw new IllegalArgumentException("work must not be null");
        if (!work.generation().equals(requestManager.currentGeneration())) {
            return;
        }

        WorldKey world = cache.currentWorldKey(work.dimensionId());
        QueryPriority priority = toQueryPriority(work.priority());

        Set<DataLayer> requestedForThisChunk = requestedLayersByChunk
                .computeIfAbsent(work.requestId(), id -> new ConcurrentHashMap<>())
                .computeIfAbsent(work.chunk(), c -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        requestedForThisChunk.addAll(work.layers());

        for (DataLayer layer : work.layers()) {
            CanonicalWorkKey key = new CanonicalWorkKey(world, work.chunk(), layer, work.generation());
            registerInterestAndAcquire(key, work.requestId(), priority);
        }
    }

    private static QueryPriority toQueryPriority(SchedulerPriority priority) {
        return switch (priority) {
            case URGENT, AGED, HIGH -> QueryPriority.HIGH;
            case NORMAL -> QueryPriority.NORMAL;
            case LOW -> QueryPriority.LOW;
        };
    }

    private void registerInterestAndAcquire(CanonicalWorkKey key, RequestId requestId, QueryPriority priority) {
        boolean[] isNew = {false};
        CanonicalWork work = canonicalWork.computeIfAbsent(key, k -> {
            isNew[0] = true;
            WDCMetrics.increment(WDCMetrics.Counter.COALESCE_WORK_CREATED);
            return new CanonicalWork(k);
        });

        if (!isNew[0]) {
            WDCMetrics.increment(WDCMetrics.Counter.COALESCE_WORK_SHARED);
            WDCMetrics.increment(WDCMetrics.Counter.COALESCE_REQUESTS_COALESCED);
            WDCMetrics.increment(WDCMetrics.Counter.MULTIPLAYER_SHARED_CANONICAL_WORK);
            if (work.interestCount() > 0) {
                WDCMetrics.increment(WDCMetrics.Counter.COALESCE_DUPLICATE_WORK_PREVENTED);
                WDCMetrics.increment(WDCMetrics.Counter.MULTIPLAYER_DUPLICATE_PLAYER_WORK_PREVENTED);
            }
        }

        boolean newInterest = work.addInterest(requestId);
        interestByRequest.computeIfAbsent(requestId, id -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(key);

        if (newInterest && (work.state() == CanonicalWorkState.OBSOLETE || work.state() == CanonicalWorkState.FAILED)) {
            work.resetForReacquire();
        }

        if (work.state() == CanonicalWorkState.NEW) {
            LayerResult<?> immediate = acquireCanonicalWork(work, priority, requestId);
            if (immediate != null) {
                deliverCompletion(work, immediate);
            }
        } else if (work.state() == CanonicalWorkState.AVAILABLE) {
            DemandResult probe = demandService.query(key.world(), key.chunk(), Set.of(key.layer()));
            LayerResult<?> layerResult = probe.layer(key.layer());
            if (layerResult != null && layerResult.isLive()) {
                deliverTo(requestId, key, layerResult);
            } else if (newInterest) {
                work.resetForReacquire();
                LayerResult<?> immediate = acquireCanonicalWork(work, priority, requestId);
                if (immediate != null) deliverCompletion(work, immediate);
            }
        }
    }

    private LayerResult<?> acquireCanonicalWork(CanonicalWork work, QueryPriority priority, RequestId requestId) {
        CanonicalWorkKey key = work.key();
        DemandResult probe = demandService.query(key.world(), key.chunk(), Set.of(key.layer()));
        LayerResult<?> layerResult = probe.layer(key.layer());

        if (layerResult != null && layerResult.isLive()) {
            WDCMetrics.increment(WDCMetrics.Counter.COALESCE_WORK_LIVE_FAST_PATH);
            return layerResult;
        }

        long start = WDCMetrics.startNanos();
        demandService.requestCurrent(key.world(), net.wdc.core.world.demand.ChunkDemand.of(
                key.chunk().chunkX(), key.chunk().chunkZ(), Set.of(key.layer()), priority, RetentionPolicy.AUTO));
        WDCMetrics.record(WDCMetrics.Timer.COALESCE_SUBMIT, System.nanoTime() - start);
        WDCMetrics.increment(WDCMetrics.Counter.COALESCE_WORK_SUBMITTED_TO_DEMAND);

        work.state(CanonicalWorkState.QUEUED);
        if (layerResult != null && layerResult.isStale()) {
            WDCMetrics.increment(WDCMetrics.Counter.COALESCE_WORK_STALE_REVALIDATING);
        }
        return null;
    }

    public boolean wasReleased(RequestId requestId, ChunkKey chunk) {
        Set<ChunkKey> released = requestId == null ? null : releasedByRequest.get(requestId);
        return released != null && released.contains(chunk);
    }

    public boolean releaseChunk(RequestId requestId, ChunkKey chunk) {
        if (requestId == null || chunk == null) return false;
        boolean released = false;
        Set<CanonicalWorkKey> keys = interestByRequest.get(requestId);
        if (keys != null) {
            for (CanonicalWorkKey key : List.copyOf(keys)) {
                if (!key.chunk().equals(chunk)) continue;
                keys.remove(key);
                released = true;
                CanonicalWork work = canonicalWork.get(key);
                if (work == null) continue;
                if (work.removeInterest(requestId)) markObsolete(work);
            }
        }
        releasedByRequest.computeIfAbsent(requestId, id -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(chunk);
        RequestResultBuffer buffer = resultBuffers.get(requestId);
        if (buffer != null) buffer.remove(chunk);
        if (released) notifySchedulerOnce(requestId, chunk);
        return released;
    }

    public void observeCompletions() {
        for (CanonicalWork work : canonicalWork.values()) {
            if (work.state().isTerminal()) continue;
            if (!work.hasInterest()) continue;

            CanonicalWorkKey key = work.key();
            if (!key.generation().equals(requestManager.currentGeneration())) {
                markObsolete(work);
                continue;
            }

            DemandResult probe = demandService.query(key.world(), key.chunk(), Set.of(key.layer()));
            LayerResult<?> layerResult = probe.layer(key.layer());
            if (layerResult == null) continue;

            if (layerResult.isLive()) {
                deliverCompletion(work, layerResult);
            } else if (layerResult.isUnavailable() && work.state() != CanonicalWorkState.QUEUED) {
                work.state(CanonicalWorkState.QUEUED);
            }
        }
    }

    private void deliverCompletion(CanonicalWork work, LayerResult<?> layerResult) {
        if (!work.markCompletionDelivered()) {
            WDCMetrics.increment(WDCMetrics.Counter.COALESCE_DUPLICATE_COMPLETION_PREVENTED);
            return;
        }
        work.state(CanonicalWorkState.AVAILABLE);

        CanonicalWorkKey key = work.key();
        for (RequestId requestId : work.interestedRequestsSnapshot()) {
            deliverTo(requestId, key, layerResult);
        }
    }

    private void deliverTo(RequestId requestId, CanonicalWorkKey key, LayerResult<?> layerResult) {
        Map<ChunkKey, Set<DataLayer>> byChunk = requestedLayersByChunk.get(requestId);
        Set<DataLayer> requestedLayers = (byChunk != null) ? byChunk.get(key.chunk()) : null;
        if (requestedLayers == null || !requestedLayers.contains(key.layer())) {
            return;
        }

        Map<DataLayer, LayerResult<?>> available = new LinkedHashMap<>();
        available.put(key.layer(), layerResult);

        boolean allRequestedLayersReady = allRequestedLayersAvailable(requestId, key.chunk(), requestedLayers);
        ChunkResult result = new ChunkResult(key.chunk(), key.generation(), available, allRequestedLayersReady);

        RequestResultBuffer buffer = resultBuffers.computeIfAbsent(requestId,
                id -> new RequestResultBuffer(config.resultBufferCapacity()));
        buffer.offer(result);
        WDCMetrics.increment(WDCMetrics.Counter.COALESCE_RESULTS_DELIVERED);

        if (allRequestedLayersReady) {
            RequestLatencyTracker tracker = latencyTracker;
            if (tracker != null) tracker.onResultDelivered(requestId);
            notifySchedulerOnce(requestId, key.chunk());
        }
    }

    private void notifySchedulerOnce(RequestId requestId, ChunkKey chunk) {
        Map<ChunkKey, Boolean> notified = chunkCompletionNotified.computeIfAbsent(requestId, id -> new ConcurrentHashMap<>());
        if (notified.putIfAbsent(chunk, Boolean.TRUE) == null) {
            scheduler.completeWork(requestId, chunk);
        }
    }

    private boolean allRequestedLayersAvailable(RequestId requestId, ChunkKey chunk, Set<DataLayer> requestedLayers) {
        for (DataLayer layer : requestedLayers) {
            Set<CanonicalWorkKey> keys = interestByRequest.get(requestId);
            if (keys == null) return false;
            boolean found = false;
            for (CanonicalWorkKey k : keys) {
                if (k.chunk().equals(chunk) && k.layer().equals(layer)) {
                    CanonicalWork w = canonicalWork.get(k);
                    if (w != null && w.state() == CanonicalWorkState.AVAILABLE) found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private void markObsolete(CanonicalWork work) {
        if (work.state() != CanonicalWorkState.OBSOLETE) {
            work.state(CanonicalWorkState.OBSOLETE);
            WDCMetrics.increment(WDCMetrics.Counter.COALESCE_WORK_OBSOLETE);
        }
    }

    public List<ChunkResult> pollCompleted(RequestId requestId, int maxResults) {
        if (requestId == null) throw new IllegalArgumentException("requestId must not be null");
        RequestResultBuffer buffer = resultBuffers.get(requestId);
        if (buffer == null) {
            buffer = resultBuffers.computeIfAbsent(requestId,
                    id -> new RequestResultBuffer(config.resultBufferCapacity()));
        }

        if (buffer.depth() == 0) {
            runLiveFastPath(requestId, maxResults);
        }

        long start = WDCMetrics.startNanos();
        List<ChunkResult> polled = buffer.poll(maxResults);
        WDCMetrics.record(WDCMetrics.Timer.COALESCE_POLL, System.nanoTime() - start);
        if (!polled.isEmpty()) WDCMetrics.add(WDCMetrics.Counter.COALESCE_RESULTS_POLLED, polled.size());

        RequestGeneration current = requestManager.currentGeneration();
        List<ChunkResult> filtered = new ArrayList<>(polled.size());
        for (ChunkResult r : polled) {
            if (r.generation().equals(current)) {
                filtered.add(r);
            } else {
                WDCMetrics.increment(WDCMetrics.Counter.COALESCE_RESULTS_REJECTED_STALE_GENERATION);
            }
        }
        return filtered;
    }

    private void runLiveFastPath(RequestId requestId, int cap) {
        RequestRecord record = requestManager.get(requestId);
        if (record == null || !record.isActive() || !requestManager.isCurrentGeneration(record)) return;
        String dimension = record.request().scope().dimensionId();
        WorldKey world = cache.currentWorldKey(dimension);
        List<ScheduledWork> live = scheduler.emitFastPath(requestId, cap,
                chunk -> cache.query(world, chunk.chunkX(), chunk.chunkZ()).isLive());
        for (ScheduledWork work : live) {
            submitScheduledWork(work);
        }
    }

    public int resultBufferDepth(RequestId requestId) {
        RequestResultBuffer buffer = resultBuffers.get(requestId);
        return buffer == null ? 0 : buffer.depth();
    }

    public void releaseRequest(RequestId requestId) {
        if (requestId == null) return;
        Set<CanonicalWorkKey> keys = interestByRequest.remove(requestId);
        if (keys != null) {
            for (CanonicalWorkKey key : keys) {
                CanonicalWork work = canonicalWork.get(key);
                if (work == null) continue;
                if (work.removeInterest(requestId)) markObsolete(work);
            }
        }
        requestedLayersByChunk.remove(requestId);
        chunkCompletionNotified.remove(requestId);
        releasedByRequest.remove(requestId);
        RequestResultBuffer buffer = resultBuffers.remove(requestId);
        if (buffer != null) buffer.clear();
        WDCMetrics.increment(WDCMetrics.Counter.COALESCE_INTEREST_CANCELED);
    }

    public void expireRequest(RequestId requestId) {
        releaseRequest(requestId);
        WDCMetrics.increment(WDCMetrics.Counter.COALESCE_INTEREST_EXPIRED);
    }

    public int pruneObsolete() {
        int removed = 0;
        for (Map.Entry<CanonicalWorkKey, CanonicalWork> entry : canonicalWork.entrySet()) {
            CanonicalWork work = entry.getValue();
            if (work.state().isTerminal() && !work.hasInterest()) {
                canonicalWork.remove(entry.getKey());
                removed++;
            }
        }
        return removed;
    }

    public int trackedCanonicalWorkCount() { return canonicalWork.size(); }

    public CoalesceStatusSnapshot statusSnapshot() {
        return new CoalesceStatusSnapshot(
                WDCMetrics.get(WDCMetrics.Counter.COALESCE_WORK_SHARED),
                WDCMetrics.get(WDCMetrics.Counter.COALESCE_DUPLICATE_WORK_PREVENTED),
                WDCMetrics.get(WDCMetrics.Counter.MULTIPLAYER_SHARED_CANONICAL_WORK));
    }

    public record CoalesceStatusSnapshot(long sharedWork, long duplicatesPrevented, long sharedChunkWork) {}

    public CanonicalWorkState stateOf(CanonicalWorkKey key) {
        CanonicalWork work = canonicalWork.get(key);
        return work == null ? null : work.state();
    }

    public int interestCountOf(CanonicalWorkKey key) {
        CanonicalWork work = canonicalWork.get(key);
        return work == null ? 0 : work.interestCount();
    }

    public void reset() {
        canonicalWork.clear();
        interestByRequest.clear();
        requestedLayersByChunk.clear();
        chunkCompletionNotified.clear();
        releasedByRequest.clear();
        resultBuffers.clear();
    }
}
