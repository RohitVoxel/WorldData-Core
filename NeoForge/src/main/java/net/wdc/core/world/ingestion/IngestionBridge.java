package net.wdc.core.world.ingestion;

import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.debug.WDCOps;
import net.wdc.core.api.internal.LightSnapshots;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;
import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.storage.ChunkKey;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class IngestionBridge {
    private static final int PENDING_LIMIT = 16_384;
    private static final int RESCAN_LIMIT = 4096;
    private static final int RESCAN_PER_DRAIN = 4;

    private static final Side CLIENT = new Side();
    private static final Side SERVER = new Side();
    private static final Map<Object, String> DIMENSION_NAMES = new ConcurrentHashMap<>();
    private static final AtomicLong DROPPED = new AtomicLong();

    private IngestionBridge() {}

    private record Pending(String dimensionId, int x, int y, int z, BlockState state) {}

    private record RescanKey(String dimensionId, int chunkX, int chunkZ) {}

    private static final class Side {
        volatile ChunkIngestionService service;
        volatile WDCCache cache;
        volatile Thread owner;
        final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();
        final AtomicInteger pendingSize = new AtomicInteger();
        final Set<RescanKey> rescan = ConcurrentHashMap.newKeySet();
    }

    @FunctionalInterface
    public interface ResyncSink {
        void request(WorldKey world, ChunkKey key, MinecraftChunkView view);
    }

    private static volatile boolean clientSuspended;
    private static volatile ResyncSink resyncSink;

    public static void setResyncSink(ResyncSink sink) {
        resyncSink = sink;
    }

    public static void suspendClient(boolean suspended) {
        clientSuspended = suspended;
        if (suspended) {
            if (SERVER.cache != null) installReadHook(SERVER, SERVER.cache);
        } else if (CLIENT.cache != null) {
            installReadHook(CLIENT, CLIENT.cache);
        }
    }

    public static boolean isClientSuspended() {
        return clientSuspended;
    }

    public static void registerClient(ChunkIngestionService service, WDCCache cache) {
        CLIENT.service = service;
        CLIENT.cache = cache;
        installReadHook(CLIENT, cache);
    }

    public static void registerServer(ChunkIngestionService service, WDCCache cache) {
        SERVER.service = service;
        SERVER.cache = cache;
        installReadHook(SERVER, cache);
    }

    public static void bindClientThread(Thread owner) {
        CLIENT.owner = owner;
    }

    public static void bindServerThread(Thread owner) {
        SERVER.owner = owner;
    }

    public static void notifyBlockChanged(boolean clientSide, ResourceKey<?> dimension,
                                          int blockX, int blockY, int blockZ, BlockState newState) {
        if (clientSide && clientSuspended) return;
        Side side = clientSide ? CLIENT : SERVER;
        if (side.service == null || side.cache == null) return;
        String dimensionId = DIMENSION_NAMES.computeIfAbsent(dimension, key -> ((ResourceKey<?>) key).location().toString());
        if (!clientSide) LightSnapshots.shared().markBlockChanged(dimensionId, blockX, blockY, blockZ);
        Thread owner = side.owner;
        if (owner == null || owner == Thread.currentThread()) {
            if (side.pendingSize.get() > 0) drain(side);
            apply(side, dimensionId, blockX, blockY, blockZ, newState);
            return;
        }
        if (side.pendingSize.get() >= PENDING_LIMIT) {
            DROPPED.incrementAndGet();
            if (side.rescan.size() < RESCAN_LIMIT) side.rescan.add(new RescanKey(dimensionId, blockX >> 4, blockZ >> 4));
            return;
        }
        side.pendingSize.incrementAndGet();
        side.pending.add(new Pending(dimensionId, blockX, blockY, blockZ, newState));
    }

    public static void notifyBlockChanged(String dimensionId, int blockX, int blockY, int blockZ,
                                          MinecraftSectionView.BlockStateKeySnapshot newState) {
        dispatch(CLIENT, dimensionId, blockX, blockY, blockZ, newState);
        dispatch(SERVER, dimensionId, blockX, blockY, blockZ, newState);
    }

    public static String dimensionName(ResourceKey<?> dimension) {
        return DIMENSION_NAMES.computeIfAbsent(dimension, key -> ((ResourceKey<?>) key).location().toString());
    }

    public static WDCChunk liveChunkForRead(boolean clientSide, String dimensionId, int chunkX, int chunkZ) {
        if (clientSide && clientSuspended) return null;
        Side side = clientSide ? CLIENT : SERVER;
        WDCCache cache = side.cache;
        if (cache == null || side.service == null) return null;
        if (side.owner != Thread.currentThread()) return null;
        if (side.pendingSize.get() > 0 || !side.rescan.isEmpty()) return null;
        return cache.liveChunk(dimensionId, chunkX, chunkZ);
    }

    public static int defaultAirIdForRead(boolean clientSide) {
        ChunkIngestionService service = (clientSide ? CLIENT : SERVER).service;
        return service == null ? GlobalBlockStateRegistry.NULL_ID : service.defaultAirId();
    }

    public static int drainPending(boolean clientSide) {
        if (clientSide && clientSuspended) return 0;
        Side side = clientSide ? CLIENT : SERVER;
        int applied = drain(side);
        repairOverflow(side, RESCAN_PER_DRAIN);
        return applied;
    }

    public static int rescanCount(boolean clientSide) {
        return (clientSide ? CLIENT : SERVER).rescan.size();
    }

    public static int pendingCount(boolean clientSide) {
        return (clientSide ? CLIENT : SERVER).pendingSize.get();
    }

    public static long droppedCount() {
        return DROPPED.get();
    }

    public static void reset() {
        for (Side side : new Side[] {CLIENT, SERVER}) {
            if (side.cache != null) side.cache.setReadHook(null);
            side.service = null;
            side.cache = null;
            side.owner = null;
            side.pending.clear();
            side.pendingSize.set(0);
            side.rescan.clear();
        }
        clientSuspended = false;
        resyncSink = null;
        DIMENSION_NAMES.clear();
    }

    public static void clearPending(boolean clientSide) {
        Side side = clientSide ? CLIENT : SERVER;
        side.pending.clear();
        side.pendingSize.set(0);
        side.rescan.clear();
    }

    private static void installReadHook(Side side, WDCCache cache) {
        if (cache != null) cache.setReadHook((world, chunkX, chunkZ) -> syncBeforeRead(side, world, chunkX, chunkZ));
    }

    private static void syncBeforeRead(Side side, WorldKey world, int chunkX, int chunkZ) {
        if (side.pendingSize.get() == 0 && side.rescan.isEmpty()) return;
        Thread owner = side.owner;
        if (owner != null && owner != Thread.currentThread()) return;
        if (side.pendingSize.get() > 0) {
            drain(side);
            WDCMetrics.increment(WDCMetrics.Counter.BRIDGE_PULL_DRAINS);
        }
        if (!side.rescan.isEmpty() && side.rescan.remove(new RescanKey(world.dimensionId(), chunkX, chunkZ))) {
            resync(side, world.dimensionId(), chunkX, chunkZ, false);
        }
    }

    private static int drain(Side side) {
        int applied = 0;
        Pending change;
        while ((change = side.pending.poll()) != null) {
            side.pendingSize.decrementAndGet();
            apply(side, change.dimensionId(), change.x(), change.y(), change.z(), change.state());
            applied++;
        }
        WDCOps.record(WDCOps.Kind.BRIDGE, applied);
        return applied;
    }

    private static void repairOverflow(Side side, int max) {
        if (side.rescan.isEmpty()) return;
        Iterator<RescanKey> iterator = side.rescan.iterator();
        int repaired = 0;
        while (repaired < max && iterator.hasNext()) {
            RescanKey key = iterator.next();
            if (side.rescan.remove(key)) {
                resync(side, key.dimensionId(), key.chunkX(), key.chunkZ(), true);
                repaired++;
            }
        }
    }

    private static void resync(Side side, String dimensionId, int chunkX, int chunkZ, boolean deferrable) {
        ChunkIngestionService service = side.service;
        WDCCache cache = side.cache;
        ChunkViewSource source = ChunkViewSourceProvider.getIfPresent();
        if (service == null || cache == null || source == null) return;
        WorldKey world = cache.currentWorldKey(dimensionId);
        MinecraftChunkView view = source.viewOf(world, new ChunkKey(chunkX, chunkZ));
        if (view == null) return;
        ResyncSink sink = resyncSink;
        if (deferrable && sink != null) {
            sink.request(world, new ChunkKey(chunkX, chunkZ), view);
        } else {
            service.ingest(world, view);
        }
        WDCMetrics.increment(WDCMetrics.Counter.BRIDGE_PULL_RESYNCS);
    }

    private static void apply(Side side, String dimensionId, int x, int y, int z, BlockState state) {
        ChunkIngestionService service = side.service;
        WDCCache cache = side.cache;
        if (service == null || cache == null) return;
        if (!service.isTracked(dimensionId, x, z)) return;
        service.onBlockStateChanged(dimensionId, cache.currentWorldKey(dimensionId), x, y, z, state);
    }

    private static void dispatch(Side side, String dimensionId, int blockX, int blockY, int blockZ,
                                 MinecraftSectionView.BlockStateKeySnapshot newState) {
        ChunkIngestionService service = side.service;
        WDCCache cache = side.cache;
        if (service == null || cache == null) return;
        service.onBlockChanged(dimensionId, cache.currentWorldKey(dimensionId), blockX, blockY, blockZ, newState);
    }
}
