package net.wdc.core.world.cellgrid;

import net.wdc.core.quality.memory.AtmosphericMemoryPersistenceService;
import net.wdc.core.quality.memory.MemoryDiagnostics;
import net.wdc.core.world.spatial.SpatialProfile;
import net.wdc.core.world.spatial.SpatialProfileGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CellGrid {

    public static final int CELL_SIZE = 16;

    private static final int HORIZONTAL_RADIUS = 3;

    private static final int VERTICAL_RADIUS = 1;

    private static final int MAX_CACHED_CELLS = 512;

    private final Map<CellCoord, WDCCell> active = new HashMap<>();

    private AtmosphericMemoryPersistenceService memoryPersistence = new AtmosphericMemoryPersistenceService();

    private String currentDimensionKey = null;

    private final LinkedHashMap<CellCoord, WDCCell> cached =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CellCoord, WDCCell> eldest) {
                    boolean evict = size() > MAX_CACHED_CELLS;
                    if (evict && currentDimensionKey != null) {
                        WDCCell cell = eldest.getValue();

                        memoryPersistence.pollLoadedResult(currentDimensionKey, cell.coord())
                                .ifPresent(snapshot -> cell.absorbLoadedMemory(
                                        snapshot.humidityMemory(), snapshot.stormInfluence(), snapshot.lastMemoryUpdateTick()));

                        memoryPersistence.enqueueEviction(currentDimensionKey, cell.coord(),
                                cell.humidityMemory(), cell.stormInfluence(), cell.lastMemoryUpdateTick());
                    }
                    return evict;
                }
            };

    private final Set<CellCoord> dirtyCoords = new HashSet<>();

    private boolean hasDirtyActiveCell = false;

    private CellCoord lastCenterCoord = null;

    private long tickCounter = 0L;

    private long structuralVersion = 0L;

    public void update(Level level, BlockPos cameraPos) {
        tickCounter++;
        currentDimensionKey = level.dimension().location().toString();
        drainMemoryLoadResults();

        CellCoord center = CellCoord.fromWorld(
                cameraPos.getX(), cameraPos.getY(), cameraPos.getZ(), CELL_SIZE);

        if (center.equals(lastCenterCoord)) {
            regenerateDirtyActiveCells(level);
            return;
        }
        lastCenterCoord = center;

        Set<CellCoord> desired = computeDesiredCoords(center);

        for (CellCoord coord : desired) {
            WDCCell cell = active.get(coord);
            if (cell == null) {
                cell = cached.remove(coord);
                if (cell == null) {
                    cell = createCell(coord, level);
                }
                active.put(coord, cell);
            }
            cell.touch(tickCounter);
        }

        var it = active.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (!desired.contains(entry.getKey())) {
                WDCCell demoted = entry.getValue();

                if (demoted.isDirty()) {
                    dirtyCoords.add(entry.getKey());
                }

                it.remove();
                cached.put(entry.getKey(), demoted);
            }
        }

        structuralVersion++;

        regenerateDirtyActiveCells(level);
    }

    public long structuralVersion() { return structuralVersion; }

    public WDCCell getCell(CellCoord coord) {
        return active.get(coord);
    }

    public WDCCell getCellIncludingCache(CellCoord coord) {
        WDCCell cell = active.get(coord);
        return cell != null ? cell : cached.get(coord);
    }

    public Collection<WDCCell> getActiveCells() {
        return Collections.unmodifiableCollection(active.values());
    }

    public SpatialProfile sampleUncached(CellCoord coord, Level level) {
        HorizonMap horizonMap = HorizonMapGenerator.generate(coord, CELL_SIZE, level);
        return SpatialProfileGenerator.generate(coord, CELL_SIZE, level, horizonMap);
    }

    public long currentTick() {
        return tickCounter;
    }

    public MemoryDiagnostics memoryDiagnostics() {
        return memoryPersistence.diagnostics();
    }

    public Collection<WDCCell> getActiveNeighbors(CellCoord coord) {
        List<WDCCell> neighbors = new ArrayList<>(6);
        int[][] offsets = {
                { 1, 0, 0}, {-1, 0, 0},
                { 0, 1, 0}, { 0,-1, 0},
                { 0, 0, 1}, { 0, 0,-1},
        };
        for (int[] o : offsets) {
            WDCCell neighbor = active.get(
                    new CellCoord(coord.x() + o[0], coord.y() + o[1], coord.z() + o[2]));
            if (neighbor != null) neighbors.add(neighbor);
        }
        return neighbors;
    }

    public void markDirty(CellCoord coord) {
        WDCCell cell = active.get(coord);
        if (cell != null) {
            cell.markDirty();
            hasDirtyActiveCell = true;
        } else {
            dirtyCoords.add(coord);
        }
    }

    public void reset() {
        if (currentDimensionKey != null) {
            drainMemoryLoadResults();
            memoryPersistence.flush(currentDimensionKey, active.values());
            memoryPersistence.flush(currentDimensionKey, cached.values());
        }

        active.clear();
        cached.clear();
        dirtyCoords.clear();
        hasDirtyActiveCell = false;
        lastCenterCoord = null;
        tickCounter = 0L;
        structuralVersion = 0L;
        currentDimensionKey = null;
    }

    public void shutdown() {
        reset();
        memoryPersistence.shutdown();
        memoryPersistence = new AtmosphericMemoryPersistenceService();
    }

    private Set<CellCoord> computeDesiredCoords(CellCoord center) {
        Set<CellCoord> desired = new HashSet<>();
        for (int dx = -HORIZONTAL_RADIUS; dx <= HORIZONTAL_RADIUS; dx++) {
            for (int dy = -VERTICAL_RADIUS; dy <= VERTICAL_RADIUS; dy++) {
                for (int dz = -HORIZONTAL_RADIUS; dz <= HORIZONTAL_RADIUS; dz++) {
                    desired.add(new CellCoord(center.x() + dx, center.y() + dy, center.z() + dz));
                }
            }
        }
        return desired;
    }

    private void regenerateDirtyActiveCells(Level level) {
        if (dirtyCoords.isEmpty() && !hasDirtyActiveCell) return;

        for (WDCCell cell : active.values()) {
            CellCoord coord = cell.coord();
            boolean flaggedExternally = dirtyCoords.remove(coord);
            if (cell.isDirty() || flaggedExternally) {
                regenerate(cell, level);
            }
        }
        hasDirtyActiveCell = false;
    }

    private void drainMemoryLoadResults() {
        if (currentDimensionKey == null || !memoryPersistence.hasLoadedResults(currentDimensionKey)) return;

        for (WDCCell cell : active.values()) {
            memoryPersistence.pollLoadedResult(currentDimensionKey, cell.coord())
                    .ifPresent(snapshot -> cell.absorbLoadedMemory(
                            snapshot.humidityMemory(), snapshot.stormInfluence(), snapshot.lastMemoryUpdateTick()));
        }
        for (WDCCell cell : cached.values()) {
            memoryPersistence.pollLoadedResult(currentDimensionKey, cell.coord())
                    .ifPresent(snapshot -> cell.absorbLoadedMemory(
                            snapshot.humidityMemory(), snapshot.stormInfluence(), snapshot.lastMemoryUpdateTick()));
        }
    }

    private WDCCell createCell(CellCoord coord, Level level) {
        BlockPos centerPos = centerPos(coord);

        Holder<Biome> biome  = level.getBiome(centerPos);
        boolean skyExposed   = level.canSeeSky(centerPos);
        HorizonMap horizonMap = HorizonMapGenerator.generate(coord, CELL_SIZE, level);
        CanopyProfile canopyProfile = CanopyProfileGenerator.generate(coord, CELL_SIZE, level);
        SpatialProfile spatialProfile = SpatialProfileGenerator.generate(coord, CELL_SIZE, level, horizonMap);

        long seed = computeDeterministicSeed(level, coord, biome);

        WDCCell cell = new WDCCell(coord, seed, biome, skyExposed, horizonMap, canopyProfile, spatialProfile, tickCounter);

        if (currentDimensionKey != null) {
            memoryPersistence.reclaimPending(currentDimensionKey, coord).ifPresentOrElse(
                    snapshot -> cell.absorbLoadedMemory(
                            snapshot.humidityMemory(), snapshot.stormInfluence(), snapshot.lastMemoryUpdateTick()),
                    () -> memoryPersistence.requestLoad(currentDimensionKey, coord)
            );
        }

        return cell;
    }

    private void regenerate(WDCCell cell, Level level) {
        CellCoord coord = cell.coord();
        BlockPos centerPos = centerPos(coord);

        Holder<Biome> biome  = level.getBiome(centerPos);
        boolean skyExposed   = level.canSeeSky(centerPos);
        HorizonMap horizonMap = HorizonMapGenerator.generate(coord, CELL_SIZE, level);
        CanopyProfile canopyProfile = CanopyProfileGenerator.generate(coord, CELL_SIZE, level);
        SpatialProfile spatialProfile = SpatialProfileGenerator.generate(coord, CELL_SIZE, level, horizonMap);

        cell.applyRegeneration(biome, skyExposed, horizonMap, canopyProfile, spatialProfile);
    }

    private BlockPos centerPos(CellCoord coord) {
        return new BlockPos(
                coord.centerWorldX(CELL_SIZE),
                coord.centerWorldY(CELL_SIZE),
                coord.centerWorldZ(CELL_SIZE));
    }

    private long computeDeterministicSeed(Level level, CellCoord coord, Holder<Biome> biome) {
        String dimensionKey = level.dimension().location().toString();
        String biomeKey = biome.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");

        long h = stableHash(dimensionKey);
        h = 31 * h + coord.x();
        h = 31 * h + coord.y();
        h = 31 * h + coord.z();
        h = 31 * h + stableHash(biomeKey);
        return h;
    }

    private static long stableHash(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }
}
