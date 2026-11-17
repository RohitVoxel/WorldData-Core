package net.wdc.core.core;

import java.util.function.Consumer;
import net.wdc.core.api.WDC;
import net.wdc.core.api.internal.LightSnapshots;
import net.wdc.core.api.internal.ServerLightPump;
import net.wdc.core.api.internal.ServerLightSource;
import net.wdc.core.api.internal.ServerWorldStateSource;
import net.wdc.core.api.internal.WorldAuthority;
import net.wdc.core.config.WDCConfig;
import net.wdc.core.config.CorePaths;
import net.wdc.core.config.SessionMarker;
import net.wdc.core.config.WorldMeta;
import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.debug.PlayerCountSource;
import net.wdc.core.debug.session.WDCSession;
import net.wdc.core.scheduling.WDCTickScheduler;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.WDCCacheProvider;
import net.wdc.core.world.cache.ClientChunkFingerprintSource;
import net.wdc.core.world.cache.ServerChunkFingerprintSource;
import net.wdc.core.world.cache.SwitchingFingerprintSource;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.redirect.ReadRedirect;
import net.wdc.core.world.capacity.CapacityController;
import net.wdc.core.world.hot.HotSections;
import net.wdc.core.world.hot.HotSectionsProvider;
import net.wdc.core.world.change.ChangeEntrypoints;
import net.wdc.core.world.change.DirtyChunkRescanner;
import net.wdc.core.world.change.WorldChangeTracker;
import net.wdc.core.world.change.WorldChangeTrackerProvider;
import net.wdc.core.world.coalesce.CoalescingCoordinator;
import net.wdc.core.world.coalesce.CoalescingCoordinatorProvider;
import net.wdc.core.world.demand.WDCDemandService;
import net.wdc.core.world.demand.WDCDemandServiceProvider;
import net.wdc.core.world.governor.GovernorPriority;
import net.wdc.core.world.governor.IngestionGovernor;
import net.wdc.core.world.governor.IngestionGovernorProvider;
import net.wdc.core.world.ingestion.ChunkIngestionService;
import net.wdc.core.world.ingestion.ChunkViewSource;
import net.wdc.core.world.ingestion.ChunkViewSourceProvider;
import net.wdc.core.world.ingestion.IngestionBridge;
import net.wdc.core.world.ingestion.LoadedChunkProbe;
import net.wdc.core.world.ingestion.LoadedChunkProbeProvider;
import net.wdc.core.world.ingestion.MinecraftChunkView;
import net.wdc.core.world.ingestion.ServerChunkAdapter;
import net.wdc.core.world.ingestion.ServerChunkViewSource;
import net.wdc.core.world.ingestion.ServerLoadedChunkProbe;
import net.wdc.core.world.request.RequestLatencyTracker;
import net.wdc.core.world.request.RequestManager;
import net.wdc.core.world.request.RequestManagerProvider;
import net.wdc.core.world.scheduler.RequestScheduler;
import net.wdc.core.world.scheduler.RequestSchedulerProvider;
import net.wdc.core.world.scheduler.ScheduledWork;
import net.wdc.core.world.spill.WDCSpillRoots;
import net.wdc.core.world.spill.ChunkSpillStore;
import net.wdc.core.world.spill.DelayedSpillPolicy;
import net.wdc.core.world.spill.DelayedSpillPolicyProvider;
import net.wdc.core.world.spill.SpillScopes;
import net.wdc.core.world.storage.ChunkKey;
import net.wdc.core.world.storage.ChunkRegistryProvider;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;
import net.wdc.core.world.storage.GlobalBlockStateRegistryProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServerObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Server");
    private static final int FIRST_DRAIN_SHARE_PERCENT = 70;
    private static final long SHUTDOWN_GRACE_MILLIS = 2000L;
    private static final long SPILL_TICK_NANOS = 1_000_000L;
    private static final long MEMORY_STEP_NANOS = 200_000L;
    private static final int MAINTENANCE_INTERVAL_TICKS = 20;
    private static final int RECONCILE_PER_TICK = 64;
    private static final int REVIVE_PER_TICK = 64;
    private static final int SWEEP_PER_PLAYER_PER_TICK = 96;
    private static final int SWEEP_MAX_RADIUS = 33;

    private static final java.util.Map<java.util.UUID, net.wdc.core.world.ingestion.ChunkSweep> SWEEPS = new java.util.HashMap<>();
    private static WDCCache chunkCache;
    private static ChunkIngestionService ingestionService;
    private static WDCDemandService demandService;
    private static IngestionGovernor governor;
    private static DelayedSpillPolicy spillPolicy;
    private static RequestManager requestManager;
    private static RequestScheduler requestScheduler;
    private static CoalescingCoordinator coalescingCoordinator;
    private static final RequestLatencyTracker REQUEST_LATENCY = new RequestLatencyTracker();
    private static final WDCTickScheduler TICK_SCHEDULER = new WDCTickScheduler();
    private static volatile MinecraftServer runningServer;
    private static final ObserverGuard OBSERVER_GUARD = new ObserverGuard("server", 8, LOGGER);
    private static boolean installed;
    private static LoadedChunkProbe previousProbe;
    private static ChunkViewSource previousViewSource;
    private static String boundWorldKey = "";
    private static java.nio.file.Path sessionMarker;

    private ServerObserver() {}

    public static synchronized void install() {
        if (installed) return;
        WDCConfig.load();
        chunkCache = WDCCacheProvider.getOrCreate(new SwitchingFingerprintSource(new ClientChunkFingerprintSource(),
                new ServerChunkFingerprintSource()));
        ChunkIngestionService created = new ChunkIngestionService(GlobalBlockStateRegistryProvider.getOrCreate(), chunkCache);
        governor = IngestionGovernorProvider.getOrCreate(chunkCache, created, WDCConfig.get().performance);
        ingestionService = governor.ingestionService();
        demandService = WDCDemandServiceProvider.getOrCreate(chunkCache);
        governor.applyConfig(WDCConfig.get().performance);
        governor.applyBudget(WDCConfig.get().budget);
        IngestionBridge.registerServer(ingestionService, chunkCache);
        WDCCache cacheRef = chunkCache;
        spillPolicy = DelayedSpillPolicyProvider.getOrCreate(() -> {
            GlobalBlockStateRegistry registry = GlobalBlockStateRegistryProvider.getOrCreate();
            return new DelayedSpillPolicy(cacheRef, new ChunkSpillStore(CorePaths.root()), registry,
                    DelayedSpillPolicy.DEFAULT_GRACE_NANOS);
        });
        WDCSpillRoots.setRequireBinding(true);
        demandService.setSpillPolicy(spillPolicy);
        requestManager = RequestManagerProvider.getOrCreate();
        requestScheduler = RequestSchedulerProvider.getOrCreate(requestManager);
        coalescingCoordinator = CoalescingCoordinatorProvider.getOrCreate(demandService, chunkCache, requestManager, requestScheduler);
        requestManager.setMetricsListener(REQUEST_LATENCY);
        coalescingCoordinator.setLatencyTracker(REQUEST_LATENCY);
        WorldChangeTracker changes = WorldChangeTrackerProvider.getOrCreate();
        ingestionService.setChangeSink(changes);
        chunkCache.setFreshnessListener(changes);
        HotSectionsProvider.getOrCreate(chunkCache, WDCConfig.get().hot);
        ReadRedirect.applyStartup(WDCConfig.get().hot);
        changes.setRebuilder((dimensionId, chunkX, chunkZ) ->
                demandService.rebuildStaleLayers(chunkCache.currentWorldKey(dimensionId), new ChunkKey(chunkX, chunkZ)));
        demandService.setRefresher(ServerObserver::refreshFromMinecraft);
        IngestionBridge.setResyncSink((world, key, view) ->
                governor.onChunkRefresh(world, key, view, GovernorPriority.ACTIVE_DEMAND));
        CapacityController.applyStartup(WDCConfig.get().capacity, chunkCache, governor, spillPolicy, demandService,
                requestManager);
        WDC.registerServerWorldStateSource(new ServerWorldStateSource(() -> runningServer));
        WDC.registerServerLightSource(new ServerLightSource(() -> runningServer));
        TICK_SCHEDULER.register(WDCTickScheduler.Phase.SLOW_SYSTEMS, () -> {
            long start = WDCMetrics.startNanos();
            for (ScheduledWork work : requestScheduler.tick()) {
                coalescingCoordinator.submitScheduledWork(work);
            }
            coalescingCoordinator.observeCompletions();
            WDCMetrics.record(WDCMetrics.Timer.SCHEDULER_PROCESSING, System.nanoTime() - start);
        });
        installed = true;
        LOGGER.info("WorldData Core server-authoritative chunk ingestion initialized.");
    }

    private static boolean refreshFromMinecraft(WorldKey world, ChunkKey chunk) {
        ChunkViewSource source = ChunkViewSourceProvider.getIfPresent();
        if (source == null) return false;
        MinecraftChunkView view = source.viewOf(world, chunk);
        if (view == null) return false;
        governor.onChunkRefresh(world, chunk, view, GovernorPriority.ACTIVE_DEMAND);
        return true;
    }

    public static void onServerStarting(MinecraftServer server, Consumer<WorldChangeTracker> dedicatedChangeRegistration) {
        install();
        WorldAuthority.serverStarted(Thread.currentThread());
        IngestionBridge.bindServerThread(Thread.currentThread());
        IngestionBridge.suspendClient(true);
        if (server.isDedicatedServer() && dedicatedChangeRegistration != null) {
            dedicatedChangeRegistration.accept(WorldChangeTrackerProvider.getOrCreate());
        }
    }

    public static void onServerStarted(MinecraftServer server) {
        runningServer = server;
        OBSERVER_GUARD.reset();
        WorldAuthority.serverStarted(Thread.currentThread());
        IngestionBridge.bindServerThread(Thread.currentThread());
        IngestionBridge.suspendClient(true);
        bindWorld(server);
        previousProbe = LoadedChunkProbeProvider.getIfPresent();
        previousViewSource = ChunkViewSourceProvider.getIfPresent();
        LoadedChunkProbeProvider.set(new ServerLoadedChunkProbe(() -> runningServer));
        ChunkViewSourceProvider.set(new ServerChunkViewSource(() -> runningServer));
        if (server.isDedicatedServer()) {
            if (SessionMarker.begin(CorePaths.root())) {
                LOGGER.warn("WorldData Core: the previous server session ended unexpectedly.");
            }
            sessionMarker = CorePaths.root();
        }
    }

    private static void bindWorld(MinecraftServer server) {
        String key = server.isDedicatedServer()
                ? SpillScopes.dedicated(server.getWorldData().getLevelName(), server.overworld().getSeed())
                : SpillScopes.singleplayer(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString(),
                        server.overworld().getSeed());
        WDCSpillRoots.bind(key);
        boundWorldKey = key;
        WorldMeta.touch(key, server.isDedicatedServer() ? "dedicated" : "singleplayer", server.getWorldData().getLevelName(),
                server.overworld().getSeed(), System.currentTimeMillis());
    }

    public static void onTickPre(MinecraftServer server) {
        WDCSession.onTickStart();
        long coreStart = System.nanoTime();
        IngestionBridge.drainPending(false);
        governor.beginTick(coreStart, server.tickRateManager().nanosecondsPerTick());
        governor.recordCoreNanos(System.nanoTime() - coreStart);
    }

    public static void onTickPost(MinecraftServer server) {
        long coreStart = System.nanoTime();
        runningServer = server;
        PlayerCountSource.setActivePlayerCount(server.getPlayerList().getPlayerCount());
        WorldChangeTracker changes = WorldChangeTrackerProvider.getOrCreate();
        governor.openDrainWindow();
        governor.drainShare(FIRST_DRAIN_SHARE_PERCENT);
        governor.runInSlice(demandService::processWithin);
        governor.runInSlice(budget -> ServerLightPump.pump(server, LightSnapshots.shared(), budget));
        governor.runPhase(WDCMetrics.Timer.PHASE_RESCAN, budget -> WorldChangeTrackerProvider.rescanner().pump(chunkCache,
                governor, ChunkViewSourceProvider.getIfPresent(), DirtyChunkRescanner.DEFAULT_PER_TICK, budget));
        governor.runPhase(WDCMetrics.Timer.PHASE_FLUSH, budget -> changes.flush(server.getTickCount(), budget));
        governor.drainShare(100);
        long housekeeping = System.nanoTime();
        TICK_SCHEDULER.tick();
        HotSections hotSections = HotSectionsProvider.get();
        if (hotSections != null) hotSections.tick();
        spillPolicy.tick(SPILL_TICK_NANOS);
        if (server.getTickCount() % MAINTENANCE_INTERVAL_TICKS == 0) governor.maintain();
        governor.reconcileLive(LoadedChunkProbeProvider.getIfPresent(), RECONCILE_PER_TICK, ServerObserver::onChunkLost);
        governor.reviveStale(LoadedChunkProbeProvider.getIfPresent(), ChunkViewSourceProvider.getIfPresent(), REVIVE_PER_TICK);
        sweepAroundPlayers(server);
        governor.advanceMemoryMeter(MEMORY_STEP_NANOS);
        WDCMetrics.record(WDCMetrics.Timer.PHASE_SCHEDULER, System.nanoTime() - housekeeping);
        governor.recordCoreNanos(System.nanoTime() - coreStart);
        WDCSession.onTickEnd();
    }

    private static void sweepAroundPlayers(MinecraftServer server) {
        net.wdc.core.world.ingestion.LoadedChunkProbe probe = LoadedChunkProbeProvider.getIfPresent();
        net.wdc.core.world.ingestion.ChunkViewSource views = ChunkViewSourceProvider.getIfPresent();
        if (probe == null || views == null) return;
        int radius = Math.min(SWEEP_MAX_RADIUS, server.getPlayerList().getViewDistance() + 1);
        java.util.Set<java.util.UUID> present = new java.util.HashSet<>();
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            present.add(player.getUUID());
            net.wdc.core.world.ingestion.ChunkSweep sweep = SWEEPS.computeIfAbsent(player.getUUID(),
                    id -> new net.wdc.core.world.ingestion.ChunkSweep());
            WorldKey world = chunkCache.currentWorldKey(player.level().dimension().location().toString());
            net.minecraft.world.level.ChunkPos center = player.chunkPosition();
            sweep.sweep(governor, chunkCache, world, probe, views, center.x, center.z, radius, SWEEP_PER_PLAYER_PER_TICK);
        }
        SWEEPS.keySet().retainAll(present);
    }

    private static void onChunkLost(WorldKey world, ChunkKey chunk) {
        governor.invalidateChunk(world, chunk);
        spillPolicy.onUnloaded(world, chunk.chunkX(), chunk.chunkZ());
    }

    public static void onChunkLoad(ServerLevel serverLevel, LevelChunk chunk) {
        if (!installed || OBSERVER_GUARD.isDisabled()) return;
        try {
            String dimensionId = serverLevel.dimension().location().toString();
            WorldKey world = chunkCache.currentWorldKey(dimensionId);
            ChunkKey key = new ChunkKey(chunk.getPos().x, chunk.getPos().z);
            governor.onChunkLoaded(world, key, new ServerChunkAdapter(dimensionId, chunk, serverLevel),
                    GovernorPriority.REQUEST_PRIORITY);
            LightSnapshots.shared().onChunkLoaded(dimensionId, key.chunkX(), key.chunkZ(),
                    serverLevel.getMinSection(), serverLevel.getMaxSection());
            OBSERVER_GUARD.success();
        } catch (RuntimeException callbackFailed) {
            OBSERVER_GUARD.failure(callbackFailed);
        }
    }

    public static void onChunkUnload(ServerLevel serverLevel, LevelChunk chunk) {
        if (!installed || OBSERVER_GUARD.isDisabled()) return;
        try {
            String dimensionId = serverLevel.dimension().location().toString();
            WorldKey world = chunkCache.currentWorldKey(dimensionId);
            ingestionService.markUnloaded(world, chunk.getPos().x, chunk.getPos().z);
            LightSnapshots.shared().dropChunk(dimensionId, chunk.getPos().x, chunk.getPos().z);
            governor.invalidateChunk(world, new ChunkKey(chunk.getPos().x, chunk.getPos().z));
            spillPolicy.onUnloaded(world, chunk.getPos().x, chunk.getPos().z);
            OBSERVER_GUARD.success();
        } catch (RuntimeException callbackFailed) {
            OBSERVER_GUARD.failure(callbackFailed);
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        WDCSession.onServerStopping();
        SWEEPS.clear();
        if (!installed) return;
        spillPolicy.shutdownFlush(SHUTDOWN_GRACE_MILLIS);
        chunkCache.invalidateSession();
        chunkCache.clearAll();
        WorldChangeTrackerProvider.getOrCreate().flush(server.getTickCount());
        WorldChangeTrackerProvider.rescanner().clear();
        ChunkRegistryProvider.resetAll();
        demandService.reset();
        governor.reset();
        requestManager.reset();
        requestScheduler.reset();
        coalescingCoordinator.reset();
        REQUEST_LATENCY.reset();
        TICK_SCHEDULER.reset();
        IngestionBridge.clearPending(false);
        IngestionBridge.bindServerThread(null);
        WDCSpillRoots.unbind();
        DelayedSpillPolicyProvider.reset();
        LoadedChunkProbeProvider.reset();
        ChunkViewSourceProvider.reset();
        if (previousProbe != null) LoadedChunkProbeProvider.set(previousProbe);
        if (previousViewSource != null) ChunkViewSourceProvider.set(previousViewSource);
        previousProbe = null;
        previousViewSource = null;
        runningServer = null;
        boundWorldKey = "";
        WorldAuthority.serverStopped();
        LightSnapshots.shared().clear();
        IngestionBridge.suspendClient(false);
    }

    public static void onServerStopped() {
        java.nio.file.Path directory = sessionMarker;
        sessionMarker = null;
        if (directory != null) SessionMarker.end(directory);
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static IngestionGovernor governor() {
        return governor;
    }

    public static WDCCache chunkCache() {
        return chunkCache;
    }

    public static WDCDemandService demandService() {
        return demandService;
    }

    public static RequestManager requestManager() {
        return requestManager;
    }

    public static RequestScheduler requestScheduler() {
        return requestScheduler;
    }

    public static CoalescingCoordinator coalescingCoordinator() {
        return coalescingCoordinator;
    }
}
