package net.wdc.core.world.spatial;

import net.wdc.core.world.cellgrid.WDCCell;
import net.wdc.core.world.cellgrid.CellCoord;
import net.wdc.core.world.cellgrid.CellGrid;
import net.wdc.core.world.cellgrid.CellGridProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public final class SpatialEnvironment {

    private SpatialEnvironment() {}

    public static SpatialEnvironmentalState getState(Level level, BlockPos pos) {
        CellGrid grid = CellGridProvider.getOrCreate();
        int cellSize = CellGrid.CELL_SIZE;

        CellCoord coord = CellCoord.fromWorld(pos.getX(), pos.getY(), pos.getZ(), cellSize);

        int neighborDx = ((pos.getX() - coord.centerWorldX(cellSize)) >= 0) ? 1 : -1;
        int neighborDz = ((pos.getZ() - coord.centerWorldZ(cellSize)) >= 0) ? 1 : -1;

        CellCoord coordXZ  = coord;
        CellCoord coordX   = new CellCoord(coord.x() + neighborDx, coord.y(), coord.z());
        CellCoord coordZ   = new CellCoord(coord.x(), coord.y(), coord.z() + neighborDz);
        CellCoord coordXZd = new CellCoord(coord.x() + neighborDx, coord.y(), coord.z() + neighborDz);

        SpatialProfile p00 = resolve(coordXZ, grid);
        SpatialProfile p10 = resolve(coordX, grid);
        SpatialProfile p01 = resolve(coordZ, grid);
        SpatialProfile p11 = resolve(coordXZd, grid);

        float tx = fractionTowardNeighbor(pos.getX(), coordXZ.centerWorldX(cellSize), cellSize, neighborDx);
        float tz = fractionTowardNeighbor(pos.getZ(), coordXZ.centerWorldZ(cellSize), cellSize, neighborDz);

        return SpatialInterpolator.bilinear(p00, p10, p01, p11, tx, tz);
    }

    private static final SpatialProfile NEUTRAL_PROFILE = SpatialProfile.neutral();

    private static SpatialProfile resolve(CellCoord coord, CellGrid grid) {
        WDCCell cell = grid.getCellIncludingCache(coord);
        if (cell != null) return cell.spatialProfile();

        grid.markDirty(coord);
        return NEUTRAL_PROFILE;
    }

    private static float fractionTowardNeighbor(int worldCoord, int centerCoord, int cellSize, int neighborDir) {
        float raw = (worldCoord - centerCoord) * neighborDir;
        return Math.clamp(raw / cellSize, 0f, 1f);
    }
}
