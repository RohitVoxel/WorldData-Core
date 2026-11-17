package net.wdc.core.world.cellgrid;

import net.wdc.core.api.changes.WDCBlockChange;
import net.wdc.core.api.changes.WDCChangeBatch;
import net.wdc.core.api.changes.WDCChangeKind;
import net.wdc.core.api.changes.WDCChangeListener;
import net.wdc.core.api.changes.WDCChunkChange;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public final class CellGridChangeBridge implements WDCChangeListener {

    private final Supplier<String> dimensionId;
    private final Consumer<CellCoord> target;
    private final IntSupplier minCellY;
    private final IntSupplier maxCellY;
    private final int cellSize;
    private final Runnable invalidateAll;

    public CellGridChangeBridge(Supplier<String> dimensionId, Consumer<CellCoord> target, IntSupplier minCellY,
                                IntSupplier maxCellY, int cellSize, Runnable invalidateAll) {
        if (dimensionId == null || target == null || minCellY == null || maxCellY == null || invalidateAll == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        if (cellSize <= 0) throw new IllegalArgumentException("cellSize must be positive");
        this.dimensionId = dimensionId;
        this.target = target;
        this.minCellY = minCellY;
        this.maxCellY = maxCellY;
        this.cellSize = cellSize;
        this.invalidateAll = invalidateAll;
    }

    @Override
    public void onChanges(WDCChangeBatch batch) {
        String current = dimensionId.get();
        if (current == null) return;
        if (!WDCChangeBatch.ALL_DIMENSIONS.equals(batch.dimensionId()) && !current.equals(batch.dimensionId())) return;
        if (batch.reset() || batch.truncated()) {
            invalidateAll.run();
            return;
        }
        Set<CellCoord> dirty = new HashSet<>();
        for (WDCChunkChange chunk : batch.chunks()) collect(chunk, dirty);
        for (CellCoord coord : dirty) target.accept(coord);
    }

    private void collect(WDCChunkChange chunk, Set<CellCoord> dirty) {
        boolean precise = chunk.kinds().size() == 1 && chunk.has(WDCChangeKind.BLOCKS_CHANGED)
                && chunk.blockDetailsComplete() && !chunk.blockChanges().isEmpty();
        if (precise) {
            for (WDCBlockChange block : chunk.blockChanges()) {
                dirty.add(CellCoord.fromWorld(block.x(), block.y(), block.z(), cellSize));
            }
            return;
        }
        int cellX = Math.floorDiv(chunk.chunkX() * 16, cellSize);
        int lastCellX = Math.floorDiv(chunk.chunkX() * 16 + 15, cellSize);
        int cellZ = Math.floorDiv(chunk.chunkZ() * 16, cellSize);
        int lastCellZ = Math.floorDiv(chunk.chunkZ() * 16 + 15, cellSize);
        int lowY = minCellY.getAsInt();
        int highY = maxCellY.getAsInt();
        if (!chunk.changedSectionYs().isEmpty() && !chunk.has(WDCChangeKind.CHUNK_LOADED)
                && !chunk.has(WDCChangeKind.CHUNK_UNLOADED) && !chunk.has(WDCChangeKind.CHUNK_REMOVED)) {
            int lowest = Integer.MAX_VALUE;
            int highest = Integer.MIN_VALUE;
            for (int sectionY : chunk.changedSectionYs()) {
                lowest = Math.min(lowest, Math.floorDiv(sectionY * 16, cellSize));
                highest = Math.max(highest, Math.floorDiv(sectionY * 16 + 15, cellSize));
            }
            lowY = Math.max(lowY, lowest);
            highY = Math.min(highY, highest);
        }
        for (int x = cellX; x <= lastCellX; x++) {
            for (int z = cellZ; z <= lastCellZ; z++) {
                for (int y = lowY; y <= highY; y++) dirty.add(new CellCoord(x, y, z));
            }
        }
    }
}
