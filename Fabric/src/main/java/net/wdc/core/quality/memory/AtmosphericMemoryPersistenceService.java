package net.wdc.core.quality.memory;

import net.wdc.core.config.CorePaths;
import net.wdc.core.world.cellgrid.WDCCell;
import net.wdc.core.world.cellgrid.CellCoord;
import net.wdc.core.world.spill.RegionSet;
import net.wdc.core.world.work.CoreThreads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class AtmosphericMemoryPersistenceService {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Memory");
    private static final int MAGIC = 0x41544D4D;
    private static final int VERSION = 1;
    private static final int QUEUE_CAPACITY = 16;
    private static final int Y_CELL_OFFSET = 32;
    private static final String MEMORY_DIRECTORY = "memory";
    private static final long SHUTDOWN_GRACE_MS = 2000L;

    private final Map<String, Map<CellCoord, CellMemorySnapshot>> pendingReclaim = new ConcurrentHashMap<>();
    private final Map<String, Map<CellCoord, CellMemorySnapshot>> loadedResults = new ConcurrentHashMap<>();

    private final Map<String, RegionSet> regionSets = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor ioExecutor;
    private final AtomicLong writesCompleted = new AtomicLong();
    private final AtomicLong loadsCompleted = new AtomicLong();

    public AtmosphericMemoryPersistenceService() {
        this.ioExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                CoreThreads.workerFactory("WorldData-Memory-IO", Thread.NORM_PRIORITY),
                (runnable, executor) -> {
                    executor.getQueue().poll();
                    executor.execute(runnable);
                }
        );
    }

    public void enqueueEviction(String dimensionKey, CellCoord coord, float humidityMemory, float stormInfluence, long tick) {
        CellMemorySnapshot snapshot = new CellMemorySnapshot(humidityMemory, stormInfluence, tick);
        pendingReclaim.computeIfAbsent(dimensionKey, k -> new ConcurrentHashMap<>()).put(coord, snapshot);
        try {
            ioExecutor.execute(() -> writeOne(dimensionKey, coord, snapshot));
        } catch (RejectedExecutionException fullOrShutdown) {
            LOGGER.debug("WorldData: memory eviction write dropped (queue full or shutting down)");
        }
    }

    public Optional<CellMemorySnapshot> reclaimPending(String dimensionKey, CellCoord coord) {
        Map<CellCoord, CellMemorySnapshot> dimEntries = pendingReclaim.get(dimensionKey);
        if (dimEntries == null) return Optional.empty();
        return Optional.ofNullable(dimEntries.remove(coord));
    }

    public void requestLoad(String dimensionKey, CellCoord coord) {
        try {
            ioExecutor.execute(() -> {
                Optional<CellMemorySnapshot> result = readOne(dimensionKey, coord);
                result.ifPresent(snapshot -> {
                    loadedResults.computeIfAbsent(dimensionKey, k -> new ConcurrentHashMap<>()).put(coord, snapshot);
                    loadsCompleted.incrementAndGet();
                });
            });
        } catch (RejectedExecutionException fullOrShutdown) {
            LOGGER.debug("WorldData: memory load request dropped (queue full or shutting down)");
        }
    }

    public boolean hasLoadedResults(String dimensionKey) {
        Map<CellCoord, CellMemorySnapshot> entries = loadedResults.get(dimensionKey);
        return entries != null && !entries.isEmpty();
    }

    public Optional<CellMemorySnapshot> pollLoadedResult(String dimensionKey, CellCoord coord) {
        Map<CellCoord, CellMemorySnapshot> entries = loadedResults.get(dimensionKey);
        if (entries == null) return Optional.empty();
        return Optional.ofNullable(entries.remove(coord));
    }

    public void flush(String dimensionKey, Collection<WDCCell> cells) {
        for (WDCCell cell : cells) {
            writeOne(dimensionKey, cell.coord(),
                    new CellMemorySnapshot(cell.humidityMemory(), cell.stormInfluence(), cell.lastMemoryUpdateTick()));
        }
    }

    public MemoryDiagnostics diagnostics() {
        int pendingReclaimCount = pendingReclaim.values().stream().mapToInt(Map::size).sum();
        return new MemoryDiagnostics(
                ioExecutor.getQueue().size(),
                pendingReclaimCount,
                writesCompleted.get(),
                loadsCompleted.get(),
                !ioExecutor.isShutdown());
    }

    public void shutdown() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        pendingReclaim.clear();
        loadedResults.clear();
        for (RegionSet set : regionSets.values()) {
            synchronized (set) {
                set.closeAll();
            }
        }
        regionSets.clear();
    }

    private void writeOne(String dimensionKey, CellCoord coord, CellMemorySnapshot snapshot) {
        RegionSet set = regionSet(dimensionKey);
        if (set == null || !inKeyRange(coord)) return;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(24);
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeFloat(snapshot.humidityMemory());
            out.writeFloat(snapshot.stormInfluence());
            out.writeLong(snapshot.lastMemoryUpdateTick());
        } catch (IOException impossible) {
            return;
        }
        try {
            synchronized (set) {
                set.putAt(coord.x() >> RegionSet.REGION_SHIFT, coord.z() >> RegionSet.REGION_SHIFT, coord.x(), keyZ(coord), bytes.toByteArray(), System.currentTimeMillis());
            }
            writesCompleted.incrementAndGet();
        } catch (IOException e) {
            LOGGER.debug("WorldData: failed to persist cell memory at {} — {}", coord, e.getMessage());
        }
    }

    private Optional<CellMemorySnapshot> readOne(String dimensionKey, CellCoord coord) {
        RegionSet set = regionSet(dimensionKey);
        if (set == null || !inKeyRange(coord)) return Optional.empty();
        byte[] payload;
        try {
            synchronized (set) {
                payload = set.getAt(coord.x() >> RegionSet.REGION_SHIFT, coord.z() >> RegionSet.REGION_SHIFT, coord.x(), keyZ(coord));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        if (payload == null) return Optional.empty();
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return Optional.empty();
            float humidity = in.readFloat();
            float storm = in.readFloat();
            long tick = in.readLong();
            return Optional.of(new CellMemorySnapshot(humidity, storm, tick));
        } catch (IOException | RuntimeException corruptOrUnreadable) {
            LOGGER.debug("WorldData: discarding unreadable cell memory entry {}", coord);
            return Optional.empty();
        }
    }

    private RegionSet regionSet(String dimensionKey) {
        if (!CorePaths.isInitialized() || !CorePaths.hasWorld()) return null;
        String worldKey = CorePaths.worldKey();
        String mapKey = worldKey + "|" + dimensionKey;
        return regionSets.computeIfAbsent(mapKey, k -> new RegionSet(
                CorePaths.dimensionDirectory(worldKey, dimensionKey).resolve(MEMORY_DIRECTORY),
                worldKey + "/" + dimensionKey + "/" + MEMORY_DIRECTORY, RegionSet.Kind.LOG));
    }

    static boolean inKeyRange(CellCoord coord) {
        return coord.y() >= -Y_CELL_OFFSET && coord.y() < Y_CELL_OFFSET;
    }

    static int keyZ(CellCoord coord) {
        return coord.z() * (Y_CELL_OFFSET * 2) + (coord.y() + Y_CELL_OFFSET);
    }
}
