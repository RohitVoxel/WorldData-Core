package net.wdc.core.world.scheduler;

import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.world.request.RequestId;
import net.wdc.core.world.request.RequestLatencyHint;
import net.wdc.core.world.request.RequestLifecycleState;
import net.wdc.core.world.request.RequestManager;
import net.wdc.core.world.request.RequestPriority;
import net.wdc.core.world.request.RequestProgress;
import net.wdc.core.world.request.RequestRecord;
import net.wdc.core.world.storage.ChunkKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class RequestScheduler {

    public interface BackpressureSource {
        int occupancy(RequestId requestId);
    }

    private static final int BACKPRESSURE_HIGH_WATER = 128;

    private final RequestManager requestManager;
    private volatile SchedulerConfig config;
    private volatile BackpressureSource backpressureSource;

    private final Map<RequestId, RequestSchedulerState> states = new ConcurrentHashMap<>();

    private final ReadySet readySet;

    private long tickCounter = 0L;

    public RequestScheduler(RequestManager requestManager, SchedulerConfig config) {
        if (requestManager == null || config == null) throw new IllegalArgumentException("args must not be null");
        config.validate();
        this.requestManager = requestManager;
        this.config = config;
        this.readySet = new ReadySet(config.maxReadySetSize);
    }

    public void applyConfig(SchedulerConfig newConfig) {
        if (newConfig == null) throw new IllegalArgumentException("config must not be null");
        newConfig.validate();
        this.config = newConfig;
        readySet.setCapacity(newConfig.maxReadySetSize);
    }

    public SchedulerConfig config() { return config; }

    public void setBackpressureSource(BackpressureSource source) { this.backpressureSource = source; }

    public int lookaheadOf(RequestId requestId) {
        RequestRecord record = requestId == null ? null : requestManager.get(requestId);
        if (record == null) return Math.clamp(config.defaultLookahead, 1, config.maxLookahead);
        return resolveLookahead(record);
    }

    public List<ScheduledWork> tick() {
        long start = WDCMetrics.startNanos();
        tickCounter++;

        syncWithRequestManager();
        ageWaitingRequests();
        expandFrontiers();

        List<ScheduledWork> selected = readySet.snapshotOrderedByPriority();
        WDCMetrics.add(WDCMetrics.Counter.REQUEST_SCHEDULER_WORK_SELECTED, selected.size());
        WDCMetrics.record(WDCMetrics.Timer.REQUEST_SCHEDULER_DECISION, System.nanoTime() - start);
        return selected;
    }

    public List<ScheduledWork> emitFastPath(RequestId requestId, int cap, Predicate<ChunkKey> isLive) {
        if (requestId == null || isLive == null || cap <= 0) return List.of();
        RequestSchedulerState state = states.get(requestId);
        if (state == null) {
            RequestRecord record = requestManager.get(requestId);
            if (record == null || !record.isActive() || !requestManager.isCurrentGeneration(record)) return List.of();
            state = states.computeIfAbsent(record.id(), id -> register(record));
        }
        if (!state.record.isActive() || !requestManager.isCurrentGeneration(state.record)) return List.of();

        int budget = Math.min(cap, config.maxActiveWork - totalActiveWork());
        if (budget <= 0) return List.of();

        List<ChunkKey> candidates = state.frontier.nextCandidates(budget);
        List<ScheduledWork> out = new ArrayList<>();
        for (ChunkKey chunk : candidates) {
            if (out.size() >= budget) break;
            if (state.isActive(chunk) || !isLive.test(chunk)) continue;
            WorkKey key = new WorkKey(state.generation, state.record.request().scope().dimensionId(),
                    chunk, state.record.request().layers(), state.requestId);
            ScheduledWork work = new ScheduledWork(key, state.requestId, state.generation, state.currentTier(), tickCounter);
            state.tryActivate(chunk);
            state.frontier.markEmitted(chunk);
            out.add(work);
        }
        if (!out.isEmpty()) {
            state.markServiced(tickCounter);
            publishProgress(state);
        }
        return out;
    }

    private void syncWithRequestManager() {
        List<RequestRecord> active = requestManager.activeRequests();
        var currentGeneration = requestManager.currentGeneration();

        for (RequestRecord record : active) {
            states.computeIfAbsent(record.id(), id -> register(record));
        }

        states.keySet().removeIf(id -> {
            RequestRecord record = requestManager.get(id);
            boolean stale = record == null
                    || record.state().isTerminal()
                    || !record.generation().equals(currentGeneration);
            if (stale) {
                readySet.removeAllForRequest(id);
                tallyTerminalReason(record);
            }
            return stale;
        });
    }

    private void tallyTerminalReason(RequestRecord record) {
        if (record == null) return;
        switch (record.state()) {
            case CANCELLED -> WDCMetrics.increment(WDCMetrics.Counter.REQUEST_SCHEDULER_WORK_CANCELED);
            case EXPIRED -> WDCMetrics.increment(WDCMetrics.Counter.REQUEST_SCHEDULER_WORK_EXPIRED);
            case COMPLETE -> WDCMetrics.increment(WDCMetrics.Counter.REQUEST_SCHEDULER_WORK_COMPLETED);
            default -> { }
        }
    }

    private RequestSchedulerState register(RequestRecord record) {
        int lookahead = resolveLookahead(record);
        RequestFrontier frontier = new RequestFrontier(record.scope(), record.accessPattern(), lookahead, config);
        SchedulerPriority initialTier = tierOf(record.priority(), record.request().latencyHint());
        return new RequestSchedulerState(record, frontier, tickCounter, initialTier);
    }

    private int resolveLookahead(RequestRecord record) {
        Integer requested = record.lookahead();
        int base = (requested != null) ? requested : config.defaultLookahead;
        return Math.clamp(base, 1, config.maxLookahead);
    }

    private SchedulerPriority tierOf(RequestPriority priority, RequestLatencyHint latencyHint) {
        if (latencyHint == RequestLatencyHint.IMMEDIATE || priority == RequestPriority.CRITICAL) return SchedulerPriority.URGENT;
        if (priority == RequestPriority.HIGH) return SchedulerPriority.HIGH;
        if (priority == RequestPriority.LOW || latencyHint == RequestLatencyHint.BACKGROUND) return SchedulerPriority.LOW;
        return SchedulerPriority.NORMAL;
    }

    private void ageWaitingRequests() {
        for (RequestSchedulerState state : states.values()) {
            boolean alreadyFavored = state.currentTier() == SchedulerPriority.URGENT || state.currentTier() == SchedulerPriority.AGED;
            if (!alreadyFavored && state.ticksInCurrentTier(tickCounter) >= config.agingThresholdTicks) {
                state.raiseTier(SchedulerPriority.AGED, tickCounter);
                WDCMetrics.increment(WDCMetrics.Counter.REQUEST_SCHEDULER_AGING_PROMOTIONS);
            }
        }
    }

    private void expandFrontiers() {
        List<RequestSchedulerState> ordered = new ArrayList<>(states.values());
        ordered.sort((a, b) -> {
            int byTier = Integer.compare(a.currentTier().tier(), b.currentTier().tier());
            if (byTier != 0) return byTier;
            return Long.compare(b.ticksSinceServiced(tickCounter), a.ticksSinceServiced(tickCounter));
        });

        int globalRemaining = config.maxActiveWork - totalActiveWork();

        for (RequestSchedulerState state : ordered) {
            if (globalRemaining <= 0) {
                WDCMetrics.increment(WDCMetrics.Counter.REQUEST_SCHEDULER_FRONTIER_STALLS);
                break;
            }
            if (!state.record.isActive()) continue;

            BackpressureSource source = backpressureSource;
            if (source != null && source.occupancy(state.requestId) >= BACKPRESSURE_HIGH_WATER) {
                WDCMetrics.increment(WDCMetrics.Counter.REQUEST_SCHEDULER_FRONTIER_STALLS);
                continue;
            }

            int perRequestBudget = Math.min(Math.max(readySet.remainingCapacity(), 1), globalRemaining);
            List<ChunkKey> candidates = state.frontier.nextCandidates(perRequestBudget);
            if (candidates.isEmpty()) continue;

            int offered = 0;
            boolean throttled = false;
            for (ChunkKey chunk : candidates) {
                if (state.isActive(chunk)) {
                    WDCMetrics.increment(WDCMetrics.Counter.REQUEST_SCHEDULER_DUPLICATE_SUPPRESSED);
                    continue;
                }
                WorkKey key = new WorkKey(state.generation, state.record.request().scope().dimensionId(),
                        chunk, state.record.request().layers(), state.requestId);
                ScheduledWork work = new ScheduledWork(key, state.requestId, state.generation,
                        state.currentTier(), tickCounter);
                ReadySet.OfferOutcome outcome = readySet.offer(work);
                if (outcome.admitted()) {
                    if (outcome.evicted() != null) bumpBackToFrontier(outcome.evicted());
                    state.tryActivate(chunk);
                    state.frontier.markEmitted(chunk);
                    offered++;
                    globalRemaining--;
                    if (globalRemaining <= 0) { throttled = true; break; }
                } else {
                    WDCMetrics.increment(WDCMetrics.Counter.REQUEST_SCHEDULER_WORK_DEFERRED);
                    throttled = true;
                    break;
                }
            }
            if (offered > 0) {
                state.markServiced(tickCounter);
                if (!throttled) {
                    state.resetTier(tierOf(state.record.priority(), state.record.request().latencyHint()), tickCounter);
                }
                WDCMetrics.increment(WDCMetrics.Counter.REQUEST_SCHEDULER_FRONTIER_EXPANSIONS);
                publishProgress(state);
            }
        }
    }

    private void bumpBackToFrontier(ScheduledWork evicted) {
        RequestSchedulerState owner = states.get(evicted.requestId());
        if (owner == null) return;
        owner.release(evicted.chunk());
        owner.frontier.unmarkEmitted(evicted.chunk());
        WDCMetrics.increment(WDCMetrics.Counter.REQUEST_SCHEDULER_WORK_DEMOTED);
    }

    private void publishProgress(RequestSchedulerState state) {
        RequestRecord record = requestManager.get(state.requestId);
        if (record == null) return;
        RequestProgress current = record.progress();
        long active = state.activeCount();
        RequestProgress updated = new RequestProgress(current.totalLogical(), current.completed(), active);
        requestManager.updateProgress(state.requestId, updated);
        if (record.state() == RequestLifecycleState.QUEUED && record.generation().equals(requestManager.currentGeneration())) {
            requestManager.advance(state.requestId, RequestLifecycleState.ACQUIRING);
        }
    }

    public void completeWork(RequestId requestId, ChunkKey chunk) {
        if (requestId == null || chunk == null) throw new IllegalArgumentException("args must not be null");
        RequestSchedulerState state = states.get(requestId);
        if (state == null) return;

        state.release(chunk);
        readySet.removeWork(requestId, chunk);
        advanceFrontierPast(state, chunk);

        RequestRecord record = requestManager.get(requestId);
        if (requestManager.isCurrentGeneration(record)) {
            RequestProgress current = record.progress();
            RequestProgress updated = new RequestProgress(
                    current.totalLogical(), current.completed() + 1, Math.max(0, current.active() - 1));
            requestManager.updateProgress(requestId, updated);
            if (updated.isComplete() && record.state() != RequestLifecycleState.COMPLETE) {
                requestManager.advance(requestId, RequestLifecycleState.COMPLETE);
            } else if (record.state() == RequestLifecycleState.ACQUIRING) {
                requestManager.advance(requestId, RequestLifecycleState.PARTIAL);
            }
        }
        WDCMetrics.increment(WDCMetrics.Counter.REQUEST_SCHEDULER_WORK_COMPLETED);
    }

    private void advanceFrontierPast(RequestSchedulerState state, ChunkKey chunk) {
        switch (state.record.accessPattern()) {
            case SEQUENTIAL, UNKNOWN -> state.frontier.advancePast(chunk);
            case RANDOM -> state.frontier.advanceRandomPast(chunk);
            default -> { }
        }
    }

    public int demoteObsoleteWork(RequestId requestId, ChunkKey chunk) {
        RequestSchedulerState state = states.get(requestId);
        if (state == null || !state.isActive(chunk)) return 0;
        state.release(chunk);
        boolean removed = readySet.removeWork(requestId, chunk);
        WDCMetrics.increment(WDCMetrics.Counter.REQUEST_SCHEDULER_WORK_DEMOTED);
        return removed ? 1 : 0;
    }

    public int activeRequestCount() { return states.size(); }

    public int totalActiveWork() {
        int sum = 0;
        for (RequestSchedulerState state : states.values()) sum += state.activeCount();
        return sum;
    }

    public int readySetSize() { return readySet.size(); }

    public SchedulerSnapshot snapshot() {
        return new SchedulerSnapshot(states.size(), totalActiveWork(), readySet.size(), config.maxActiveWork, config.maxReadySetSize);
    }

    @SuppressWarnings("unused")
    Map<RequestId, RequestSchedulerState> statesView() { return Map.copyOf(states); }

    public void reset() {
        states.clear();
        readySet.clear();
        tickCounter = 0L;
    }

    public record SchedulerSnapshot(
            int activeLogicalRequests, int activeWork, int readySetSize, int maxActiveWork, int maxReadySetSize) {
    }
}
