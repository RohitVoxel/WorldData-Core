package net.wdc.core.core;

import net.wdc.core.camera.CameraManager;
import net.wdc.core.camera.CameraSnapshot;
import net.wdc.core.api.client.ClientApi;
import net.wdc.core.api.client.ClientApiImpl;
import net.wdc.core.api.internal.ClientApiRegistry;
import net.wdc.core.api.internal.ClientLightSource;
import net.wdc.core.api.internal.ClientWorldStateSource;
import net.wdc.core.compat.CompatibilityManager;
import net.wdc.core.config.WDCConfig;
import net.wdc.core.debug.WDCDebugCommands;
import net.wdc.core.redirect.ReadRedirect;
import net.wdc.core.world.capacity.CapacityController;
import net.wdc.core.world.hot.HotSections;
import net.wdc.core.world.hot.HotSectionsProvider;
import net.wdc.core.api.internal.WorldAuthority;
import net.wdc.core.config.CorePaths;
import net.wdc.core.world.cache.SwitchingFingerprintSource;
import net.wdc.core.world.cache.ServerChunkFingerprintSource;
import net.wdc.core.world.ingestion.MinecraftChunkView;
import net.wdc.core.world.ingestion.ChunkViewSource;
import net.wdc.core.config.SessionMarker;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.WDCCacheProvider;
import net.wdc.core.world.cache.ClientChunkFingerprintSource;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.api.changes.WDCChangeFilter;
import net.wdc.core.world.cellgrid.CellGrid;
import net.wdc.core.world.cellgrid.CellGridChangeBridge;
import net.wdc.core.world.cellgrid.CellGridProvider;
import net.wdc.core.world.change.ChangeEntrypoints;
import net.wdc.core.world.change.ModChangeEntrypoints;
import net.wdc.core.world.change.DirtyChunkRescanner;
import net.wdc.core.world.change.WorldChangeTracker;
import net.wdc.core.world.change.WorldChangeTrackerProvider;
import net.wdc.core.world.ingestion.ChunkViewSourceProvider;
import net.wdc.core.world.ingestion.ClientChunkViewSource;
import net.wdc.core.world.demand.WDCDemandService;
import net.wdc.core.world.demand.WDCDemandServiceProvider;
import net.wdc.core.client.identity.WDCBlockIdentity;
import net.wdc.core.scheduling.WDCTickScheduler;
import net.wdc.core.world.governor.GovernorPriority;
import net.wdc.core.world.governor.IngestionGovernor;
import net.wdc.core.world.governor.IngestionGovernorProvider;
import net.wdc.core.world.ingestion.ChunkIngestionService;
import net.wdc.core.world.ingestion.ClientChunkAdapter;
import net.wdc.core.world.ingestion.ClientChunkPriorityHint;
import net.wdc.core.world.ingestion.ClientLoadedChunkProbe;
import net.wdc.core.world.ingestion.IngestionBridge;
import net.wdc.core.world.ingestion.LoadedChunkProbeProvider;
import net.wdc.core.world.spill.WDCSpillRoots;
import net.wdc.core.world.spill.SpillScopes;
import net.wdc.core.world.storage.ChunkKey;
import net.wdc.core.world.spill.ChunkSpillStore;
import net.wdc.core.world.spill.DelayedSpillPolicy;
import net.wdc.core.world.spill.DelayedSpillPolicyProvider;
import net.wdc.core.world.storage.ChunkRegistryProvider;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;
import net.wdc.core.world.storage.GlobalBlockStateRegistryProvider;
import net.wdc.core.world.request.RequestManager;
import net.wdc.core.world.request.RequestManagerProvider;
import net.wdc.core.world.request.RequestLatencyTracker;
import net.wdc.core.world.scheduler.RequestScheduler;
import net.wdc.core.world.scheduler.RequestSchedulerProvider;
import net.wdc.core.world.scheduler.ScheduledWork;
import net.wdc.core.world.coalesce.CoalescingCoordinator;
import net.wdc.core.world.coalesce.CoalescingCoordinatorProvider;
import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.debug.PlayerCountSource;
import net.minecraft.client.Camera;
import com.mojang.blaze3d.platform.InputConstants;
import net.wdc.core.debug.HotOverlay;
import net.wdc.core.debug.HotOverlayRenderer;
import net.wdc.core.debug.OpsPanel;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

@Mod(value = WDCCommon.MOD_ID, dist = Dist.CLIENT)
public final class WDCClient {

    private static final CompatibilityManager COMPATIBILITY_MANAGER = new CompatibilityManager();
    private static final WDCTickScheduler TICK_SCHEDULER = new WDCTickScheduler();
    private static final long SPILL_TICK_NANOS = 1_000_000L;
    private static final int MAINTENANCE_INTERVAL_TICKS = 20;
    private static final int RECONCILE_PER_TICK = 64;
    private static final WDCCache CHUNK_CACHE = WDCCacheProvider.getOrCreate(new SwitchingFingerprintSource(
            new ClientChunkFingerprintSource(), new ServerChunkFingerprintSource()));
    private static final ChunkIngestionService INGESTION_SERVICE = new ChunkIngestionService(
            GlobalBlockStateRegistryProvider.getOrCreate(), CHUNK_CACHE);
    private static final WDCDemandService DEMAND_SERVICE = WDCDemandServiceProvider.getOrCreate(CHUNK_CACHE);
    private static final IngestionGovernor GOVERNOR = IngestionGovernorProvider.getOrCreate(
            CHUNK_CACHE, INGESTION_SERVICE, WDCConfig.get().performance);
    private static final DelayedSpillPolicy SPILL_POLICY = DelayedSpillPolicyProvider.getOrCreate(() -> {
        GlobalBlockStateRegistry registry = GlobalBlockStateRegistryProvider.getOrCreate();
        return new DelayedSpillPolicy(CHUNK_CACHE, new ChunkSpillStore(spillRoot()), registry,
                DelayedSpillPolicy.DEFAULT_GRACE_NANOS);
    });
    private static final RequestManager REQUEST_MANAGER = RequestManagerProvider.getOrCreate();
    private static final RequestScheduler REQUEST_SCHEDULER = RequestSchedulerProvider.getOrCreate(REQUEST_MANAGER);
    private static final CoalescingCoordinator COALESCING_COORDINATOR = CoalescingCoordinatorProvider.getOrCreate(
            DEMAND_SERVICE, CHUNK_CACHE, REQUEST_MANAGER, REQUEST_SCHEDULER);
    private static final RequestLatencyTracker REQUEST_LATENCY = new RequestLatencyTracker();

    private static final ClientApi CLIENT_API = new ClientApiImpl();

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Client");
    private static final ObserverGuard OBSERVER_GUARD = new ObserverGuard("client", 8, LOGGER);

    private static ResourceKey<Level> currentDimension = null;
    private static boolean initialized = false;
    private static final KeyMapping OPS_EXTERNAL_KEY = new KeyMapping("key.worlddata_core.toggle_ops_external",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "key.categories.worlddata_core");
    private static final KeyMapping OPS_INTERNAL_KEY = new KeyMapping("key.worlddata_core.toggle_ops_internal",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.worlddata_core");

    private static final KeyMapping HOT_OVERLAY_KEY = new KeyMapping("key.worlddata_core.toggle_hot_overlay",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, "key.categories.worlddata_core");

    public WDCClient(IEventBus modBus) {
        init();

        modBus.addListener(WDCClient::onRegisterKeys);
        modBus.addListener(WDCClient::onClientSetup);
        modBus.addListener(WDCClient::onLoadComplete);

        WDCDebugCommands.register();

        NeoForge.EVENT_BUS.addListener(WDCClient::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(WDCClient::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WDCClient::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, WDCClient::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(WDCClient::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(WDCClient::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(WDCClient::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(WDCClient::onGameShuttingDown);
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPS_EXTERNAL_KEY);
        event.register(OPS_INTERNAL_KEY);
        event.register(HOT_OVERLAY_KEY);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> IngestionBridge.bindClientThread(Thread.currentThread()));
    }

    private static void onLoadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(() -> {
            ChangeEntrypoints.registerAll(WorldChangeTrackerProvider.getOrCreate(),
                    () -> ModChangeEntrypoints.discover(WDCClient::logChangeEntrypointFailure),
                    WDCClient::logChangeEntrypointFailure);
        });
    }

    private static void logChangeEntrypointFailure(Throwable failure) {
        LOGGER.warn("WorldData Core: a change entrypoint could not be registered", failure);
    }

    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        Minecraft mc = Minecraft.getInstance();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            if (mc.level == null || mc.cameraEntity == null) return;

            onRenderStart(
                    event.getCamera(),
                    event.getProjectionMatrix(),
                    event.getFrustum(),
                    event.getPartialTick().getGameTimeDeltaPartialTick(true)
            );
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            if (mc.level == null) return;

            onRenderAfterTranslucent(event.getProjectionMatrix(), event.getCamera());
            HotOverlayRenderer.render(event.getPoseStack(), event.getCamera());
        }
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft client = Minecraft.getInstance();
        OBSERVER_GUARD.reset();
        String scope = "";
        if (client.getSingleplayerServer() != null) {
            var singleplayer = client.getSingleplayerServer();
            var root = singleplayer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
            scope = SpillScopes.singleplayer(root == null ? null : root.toString(), singleplayer.overworld().getSeed());
            WDCSpillRoots.bind(scope);
        } else if (client.getCurrentServer() != null) {
            scope = SpillScopes.multiplayer(client.getCurrentServer().ip);
            WDCSpillRoots.bind(scope);
        } else {
            WDCSpillRoots.unbind();
        }
    }

    private static void onClientTickPre(ClientTickEvent.Pre event) {
        onClientTickStart();
    }

    private static void onClientTickPost(ClientTickEvent.Post event) {
        while (OPS_EXTERNAL_KEY.consumeClick()) OpsPanel.toggleExternal();
        while (OPS_INTERNAL_KEY.consumeClick()) OpsPanel.toggleInternal();
        while (HOT_OVERLAY_KEY.consumeClick()) HotOverlay.toggle();
        onClientTick();
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ClientLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        onChunkLoaded(level.dimension().location().toString(), chunk, level);
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ClientLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        onChunkUnloaded(level.dimension().location().toString(), chunk.getPos());
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        onDisconnect();
    }

    private static void onGameShuttingDown(GameShuttingDownEvent event) {
        SessionMarker.end(sessionDir());
        DelayedSpillPolicyProvider.reset();
    }

    private static Path sessionDir() {
        WDCConfig.ensurePaths();
        return CorePaths.root();
    }

    private static void init() {
        if (initialized) return;
        initialized = true;

        WDCConfig.load();
        OpsPanel.loadFrom(WDCConfig.get());
        GOVERNOR.applyConfig(WDCConfig.get().performance);
        GOVERNOR.applyBudget(WDCConfig.get().budget);
        COMPATIBILITY_MANAGER.initialize();
        IngestionBridge.registerClient(INGESTION_SERVICE, CHUNK_CACHE);
        WDCSpillRoots.setRequireBinding(true);
        DEMAND_SERVICE.setSpillPolicy(SPILL_POLICY);
        REQUEST_MANAGER.setMetricsListener(REQUEST_LATENCY);
        COALESCING_COORDINATOR.setLatencyTracker(REQUEST_LATENCY);
        LoadedChunkProbeProvider.set(new ClientLoadedChunkProbe());
        ChunkViewSourceProvider.set(new ClientChunkViewSource());
        DEMAND_SERVICE.setRefresher(WDCClient::refreshFromClientWorld);
        IngestionBridge.setResyncSink((world, key, view) ->
                GOVERNOR.onChunkRefresh(world, key, view, GovernorPriority.ACTIVE_DEMAND));
        WorldChangeTracker changes = WorldChangeTrackerProvider.getOrCreate();
        INGESTION_SERVICE.setChangeSink(changes);
        CHUNK_CACHE.setFreshnessListener(changes);
        HotSectionsProvider.getOrCreate(CHUNK_CACHE, WDCConfig.get().hot);
        ReadRedirect.applyStartup(WDCConfig.get().hot);
        changes.setRebuilder((dimensionId, chunkX, chunkZ) ->
                DEMAND_SERVICE.rebuildStaleLayers(CHUNK_CACHE.currentWorldKey(dimensionId), new ChunkKey(chunkX, chunkZ)));
        changes.subscribe(WDCChangeFilter.all().withoutBlockDetails(), new CellGridChangeBridge(
                () -> {
                    Level level = Minecraft.getInstance().level;
                    return level == null ? null : level.dimension().location().toString();
                },
                coord -> {
                    CellGrid grid = CellGridProvider.getOrCreate();
                    if (grid.getCellIncludingCache(coord) != null) grid.markDirty(coord);
                },
                () -> {
                    Level level = Minecraft.getInstance().level;
                    return level == null ? 0 : Math.floorDiv(level.getMinBuildHeight(), CellGrid.CELL_SIZE);
                },
                () -> {
                    Level level = Minecraft.getInstance().level;
                    return level == null ? 0 : Math.floorDiv(level.getMaxBuildHeight() - 1, CellGrid.CELL_SIZE);
                },
                CellGrid.CELL_SIZE,
                () -> CellGridProvider.getOrCreate().reset()));
        if (SessionMarker.begin(sessionDir())) {
            LOGGER.warn("WorldData Core: the previous session ended unexpectedly.");
        }
        CapacityController.applyStartup(WDCConfig.get().capacity, CHUNK_CACHE, GOVERNOR, SPILL_POLICY, DEMAND_SERVICE,
                REQUEST_MANAGER);
        ClientApiRegistry.register(() -> CLIENT_API, new ClientWorldStateSource(), new ClientLightSource());

        TICK_SCHEDULER.register(WDCTickScheduler.Phase.PIPELINE, WDCClient::runPipelinePhase);
        TICK_SCHEDULER.register(WDCTickScheduler.Phase.OVERLAY, WDCClient::runOverlayPhase);
        TICK_SCHEDULER.register(WDCTickScheduler.Phase.ENVIRONMENT, WDCClient::runEnvironmentPhase);
        TICK_SCHEDULER.register(WDCTickScheduler.Phase.SLOW_SYSTEMS, WDCClient::runSlowSystemsPhase);
    }

    private static void onRenderStart(Camera camera, Matrix4f projectionMatrix, Frustum frustum, float tickDelta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        CameraManager.publish(camera, projectionMatrix, frustum, tickDelta);

        CameraSnapshot snapshot = CameraManager.get();
        if (snapshot != null && !WorldAuthority.isServerAuthoritative()) {
            GOVERNOR.recordPositionSample(snapshot.position().x, snapshot.position().y, snapshot.position().z,
                    System.nanoTime());
        }

        ResourceKey<Level> newDimension = mc.level.dimension();
        boolean dimensionEntered = (currentDimension == null || !currentDimension.equals(newDimension));
        if (currentDimension != null && !currentDimension.equals(newDimension)) {
            handleDimensionChange();
        }
        currentDimension = newDimension;
        if (dimensionEntered) {
            WDCBlockIdentity.enterDimension(newDimension.location().toString());
        }
    }

    @SuppressWarnings("unused")
    private static void onRenderAfterTranslucent(Matrix4f projectionMatrix, Camera camera) {
    }

    private static void onClientTickStart() {
        if (WorldAuthority.isServerAuthoritative()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        long coreStart = System.nanoTime();
        IngestionBridge.drainPending(true);
        GOVERNOR.beginTick(coreStart, mc.level.tickRateManager().nanosecondsPerTick());
        GOVERNOR.recordCoreNanos(System.nanoTime() - coreStart);
    }

    private static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (WorldAuthority.isServerAuthoritative()) {
            if (mc.level != null && mc.player != null) TICK_SCHEDULER.tick();
            return;
        }
        if (mc.level != null && mc.player != null) {
            long coreStart = System.nanoTime();
            PlayerCountSource.setActivePlayerCount(1);
            TICK_SCHEDULER.tick();
            HotSections hotSections = HotSectionsProvider.get();
            if (hotSections != null) hotSections.tick();
            runBudgetedDrain(mc.level.getGameTime());
            WorldChangeTrackerProvider.getOrCreate().flush(mc.level.getGameTime());
            GOVERNOR.recordCoreNanos(System.nanoTime() - coreStart);
            GOVERNOR.sampleMemoryIfDue();
        } else {
            PlayerCountSource.setActivePlayerCount(0);
            WorldChangeTrackerProvider.getOrCreate().flush(0L);
        }
    }

    private static void runBudgetedDrain(long gameTime) {
        GOVERNOR.openDrainWindow();
        GOVERNOR.drain();
        GOVERNOR.runInSlice(DEMAND_SERVICE::processWithin);
        GOVERNOR.runInSlice(budget -> WorldChangeTrackerProvider.rescanner().pump(CHUNK_CACHE, GOVERNOR,
                ChunkViewSourceProvider.getIfPresent(), DirtyChunkRescanner.DEFAULT_PER_TICK, budget));
        SPILL_POLICY.tick(SPILL_TICK_NANOS);
        if (gameTime % MAINTENANCE_INTERVAL_TICKS == 0L) GOVERNOR.maintain();
        GOVERNOR.reconcileLive(LoadedChunkProbeProvider.getIfPresent(), RECONCILE_PER_TICK, (world, chunk) -> {
            GOVERNOR.invalidateChunk(world, chunk);
            SPILL_POLICY.onUnloaded(world, chunk.chunkX(), chunk.chunkZ());
        });
        GOVERNOR.reviveStale(LoadedChunkProbeProvider.getIfPresent(), ChunkViewSourceProvider.getIfPresent(), RECONCILE_PER_TICK);
    }

    private static void onChunkLoaded(String dimensionId, LevelChunk chunk, Level level) {
        if (OBSERVER_GUARD.isDisabled()) return;
        try {
            WDCBlockIdentity.onChunkLoaded(chunk.getPos());
            if (WorldAuthority.isServerAuthoritative()) return;
            WorldKey world = CHUNK_CACHE.currentWorldKey(dimensionId);
            ChunkKey key = new ChunkKey(chunk.getPos().x, chunk.getPos().z);
            CameraSnapshot camera = CameraManager.get();
            GovernorPriority priority = camera == null
                    ? GovernorPriority.DISTANCE
                    : ClientChunkPriorityHint.forChunk(camera, chunk.getPos(), GOVERNOR.currentBlocksPerSecond());
            GOVERNOR.onChunkLoaded(world, key, new ClientChunkAdapter(dimensionId, chunk, level), priority);
            OBSERVER_GUARD.success();
        } catch (RuntimeException callbackFailed) {
            OBSERVER_GUARD.failure(callbackFailed);
        }
    }

    private static void onChunkUnloaded(String dimensionId, ChunkPos chunk) {
        if (OBSERVER_GUARD.isDisabled()) return;
        try {
            WDCBlockIdentity.onChunkUnloaded(chunk);
            if (WorldAuthority.isServerAuthoritative()) return;
            WorldKey world = CHUNK_CACHE.currentWorldKey(dimensionId);
            INGESTION_SERVICE.markUnloaded(world, chunk.x, chunk.z);
            GOVERNOR.invalidateChunk(world, new ChunkKey(chunk.x, chunk.z));
            SPILL_POLICY.onUnloaded(world, chunk.x, chunk.z);
            OBSERVER_GUARD.success();
        } catch (RuntimeException callbackFailed) {
            OBSERVER_GUARD.failure(callbackFailed);
        }
    }

    private static boolean refreshFromClientWorld(WorldKey world, ChunkKey chunk) {
        ChunkViewSource source = ChunkViewSourceProvider.getIfPresent();
        if (source == null) return false;
        MinecraftChunkView view = source.viewOf(world, chunk);
        if (view == null) return false;
        GOVERNOR.onChunkRefresh(world, chunk, view, GovernorPriority.ACTIVE_DEMAND);
        return true;
    }

    private static Path spillRoot() {
        WDCConfig.ensurePaths();
        return CorePaths.root();
    }

    private static void onDisconnect() {
        fullReset();
        IngestionBridge.clearPending(true);
        WDCSpillRoots.unbind();
    }

    private static void runPipelinePhase() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        CameraSnapshot cameraSnapshot = CameraManager.get();
        if (cameraSnapshot == null) return;

        BlockPos cameraBlockPos = BlockPos.containing(cameraSnapshot.position());
        CellGridProvider.getOrCreate().update(mc.level, cameraBlockPos);
    }

    private static void runOverlayPhase() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        float overlayPhaseDelta = 1.0f / 20.0f * 4;
        WDCBlockIdentity.tick(mc.player.blockPosition(), overlayPhaseDelta);
    }

    private static void runEnvironmentPhase() {
    }

    private static void runSlowSystemsPhase() {
        if (WorldAuthority.isServerAuthoritative()) return;
        runRequestPipeline();
    }

    private static void runRequestPipeline() {
        long start = WDCMetrics.startNanos();
        for (ScheduledWork work : REQUEST_SCHEDULER.tick()) {
            COALESCING_COORDINATOR.submitScheduledWork(work);
        }
        COALESCING_COORDINATOR.observeCompletions();
        WDCMetrics.record(WDCMetrics.Timer.SCHEDULER_PROCESSING, WDCMetrics.startNanos() - start);
    }

    private static void handleDimensionChange() {
        CellGridProvider.getOrCreate().reset();
        if (WorldAuthority.isServerAuthoritative()) return;
        TICK_SCHEDULER.reset();
        DEMAND_SERVICE.reset();
        REQUEST_MANAGER.resetGeneration();
        REQUEST_SCHEDULER.reset();
        COALESCING_COORDINATOR.reset();
        if (currentDimension != null) {
            GOVERNOR.invalidateWorld(CHUNK_CACHE.currentWorldKey(currentDimension.location().toString()));
        }
    }

    private static void fullReset() {
        CameraManager.reset();
        CellGridProvider.getOrCreate().shutdown();
        COMPATIBILITY_MANAGER.reset();
        TICK_SCHEDULER.reset();
        currentDimension = null;

        CHUNK_CACHE.invalidateSession();
        CHUNK_CACHE.clearAll();
        ChunkRegistryProvider.resetAll();
        DEMAND_SERVICE.reset();
        GOVERNOR.reset();

        REQUEST_MANAGER.reset();
        REQUEST_SCHEDULER.reset();
        COALESCING_COORDINATOR.reset();
        REQUEST_LATENCY.reset();

        SPILL_POLICY.reset();
    }

    @SuppressWarnings("unused")
    public static CompatibilityManager getCompatibilityManager() { return COMPATIBILITY_MANAGER; }

    @SuppressWarnings("unused")
    public static WDCTickScheduler getTickScheduler() { return TICK_SCHEDULER; }

    @SuppressWarnings("unused")
    public static WDCCache getChunkCache() { return CHUNK_CACHE; }

    @SuppressWarnings("unused")
    public static WDCDemandService getDemandService() { return DEMAND_SERVICE; }

    @SuppressWarnings("unused")
    public static IngestionGovernor getIngestionGovernor() { return GOVERNOR; }

    @SuppressWarnings("unused")
    public static RequestManager getRequestManager() { return REQUEST_MANAGER; }

    @SuppressWarnings("unused")
    public static RequestScheduler getRequestScheduler() { return REQUEST_SCHEDULER; }

    @SuppressWarnings("unused")
    public static CoalescingCoordinator getCoalescingCoordinator() { return COALESCING_COORDINATOR; }
}
