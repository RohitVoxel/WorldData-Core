package net.wdc.core.client.identity;

import net.wdc.core.world.identity.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class WDCBlockIdentity {

    private WDCBlockIdentity() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Identity");
    private static final int DEFAULT_RENDER_DISTANCE_CHUNKS = 12;
    private static final float PENDING_PRUNE_CYCLE_SECONDS = 30f;

    private static final BlockIdentityStore STORE = new BlockIdentityStore();
    private static final IdentityPendingUnloadedStore PENDING_UNLOADED = new IdentityPendingUnloadedStore();

    private static IdentityPersistenceService persistenceService = new IdentityPersistenceService();
    private static IdentityPersistenceCoordinator persistence =
            new IdentityPersistenceCoordinator(STORE, persistenceService);

    private static String currentDimensionKey = null;
    private static float pendingPruneAccumulatedSeconds = 0f;

    public static BlockIdentityStore store() {
        return STORE;
    }

    public static IdentityPersistenceService persistenceService() {
        return persistenceService;
    }
    public static long seedFor(BlockPos pos) {
        return STORE.assignIfAbsent(pos);
    }

    public static long saltedSeedFor(BlockPos pos, String effectSalt) {
        return IdentitySeedFactory.salted(seedFor(pos), effectSalt);
    }

    public static void notifyIdentityLost(BlockPos pos) {
        STORE.remove(pos);
    }

    public static void enterDimension(String dimensionKey) {
        currentDimensionKey = dimensionKey;
        persistence.requestLoad(dimensionKey);
    }

    public static void onChunkUnloaded(ChunkPos chunk) {
        List<IdentityRemovedEntry> removedEntries = STORE.removeChunk(chunk);
        if (removedEntries.isEmpty()) return;

        List<BlockPos> positions = new ArrayList<>(removedEntries.size());
        List<Long> seeds = new ArrayList<>(removedEntries.size());
        for (IdentityRemovedEntry entry : removedEntries) {
            positions.add(entry.pos());
            seeds.add(entry.seed());
        }

        PENDING_UNLOADED.store(chunk, positions, seeds);
    }

    public static void onChunkLoaded(ChunkPos chunk) {
        IdentityPendingUnloadedStore.RestoredChunk restored = PENDING_UNLOADED.take(chunk);
        if (restored.isEmpty()) return;

        List<BlockPos> positions = restored.positions();
        List<Long> seeds = restored.seeds();
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            if (STORE.has(pos)) continue;
            STORE.set(pos, seeds.get(i));
        }
    }

    public static void tick(BlockPos playerPos, float deltaSeconds) {
        persistence.drainLoad();
        runPeriodicPendingPrune(playerPos, deltaSeconds);
    }

    private static void runPeriodicPendingPrune(BlockPos playerPos, float deltaSeconds) {
        pendingPruneAccumulatedSeconds += Math.max(0f, deltaSeconds);
        if (pendingPruneAccumulatedSeconds < PENDING_PRUNE_CYCLE_SECONDS) return;
        pendingPruneAccumulatedSeconds = 0f;

        if (PENDING_UNLOADED.pendingPositionCount() == 0) return;

        ChunkPos playerChunk = new ChunkPos(playerPos);
        int renderDistance = currentRenderDistanceChunks();

        int droppedFar = PENDING_UNLOADED.pruneFar(playerChunk, renderDistance);
        int droppedOverCap = PENDING_UNLOADED.pruneToCapByDistance(playerChunk);

        if (droppedFar > 0 || droppedOverCap > 0) {
            LOGGER.debug("Pruned pending-unload identity store: {} out of range, {} over the {}-entry cap.",
                    droppedFar, droppedOverCap, IdentityPendingUnloadedStore.MAX_ENTRIES);
        }
    }

    private static int currentRenderDistanceChunks() {
        try {
            Object value = Minecraft.getInstance().options.renderDistance().get();
            if (value instanceof Integer distance) return distance;
        } catch (Exception ignored) {

        }
        return DEFAULT_RENDER_DISTANCE_CHUNKS;
    }

    public static void reset() {
        reconcilePendingBeforeSave();
        persistence.save(currentDimensionKey);
        clearLiveState();
    }

    public static void shutdown() {
        reconcilePendingBeforeSave();
        persistence.saveBlocking(currentDimensionKey);
        clearLiveState();
        persistenceService.shutdown();
        persistenceService = new IdentityPersistenceService();
        persistence = new IdentityPersistenceCoordinator(STORE, persistenceService);
    }

    private static void reconcilePendingBeforeSave() {
        if (PENDING_UNLOADED.pendingPositionCount() == 0) return;

        for (ChunkPos chunk : PENDING_UNLOADED.pendingChunks()) {
            IdentityPendingUnloadedStore.RestoredChunk entry = PENDING_UNLOADED.take(chunk);
            List<BlockPos> positions = entry.positions();
            List<Long> seeds = entry.seeds();
            for (int i = 0; i < positions.size(); i++) {
                BlockPos pos = positions.get(i);
                if (STORE.has(pos)) continue;
                STORE.set(pos, seeds.get(i));
            }
        }
    }

    private static void clearLiveState() {
        STORE.reset();
        PENDING_UNLOADED.reset();
        persistence.cancelPendingLoad();
        currentDimensionKey = null;
        pendingPruneAccumulatedSeconds = 0f;
    }
}
