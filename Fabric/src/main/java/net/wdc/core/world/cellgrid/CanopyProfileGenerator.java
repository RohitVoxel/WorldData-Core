package net.wdc.core.world.cellgrid;

import net.wdc.core.redirect.RedirectGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

final class CanopyProfileGenerator {

    private CanopyProfileGenerator() {}

    private static final int HORIZONTAL_PROBE_OFFSET = 4;

    static CanopyProfile generate(CellCoord coord, int cellSize, Level level) {
        int centerX = coord.centerWorldX(cellSize);
        int centerY = coord.centerWorldY(cellSize);
        int centerZ = coord.centerWorldZ(cellSize);

        int[][] sampleOffsets = {
                {0, 0},
                {HORIZONTAL_PROBE_OFFSET, 0}, {-HORIZONTAL_PROBE_OFFSET, 0},
                {0, HORIZONTAL_PROBE_OFFSET}, {0, -HORIZONTAL_PROBE_OFFSET}
        };

        boolean[][] hits = new boolean[CanopyProfile.SAMPLE_COUNT][];

        BlockPos.MutableBlockPos probePos = new BlockPos.MutableBlockPos();

        for (int i = 0; i < sampleOffsets.length; i++) {
            int sampleX = centerX + sampleOffsets[i][0];
            int sampleZ = centerZ + sampleOffsets[i][1];

            hits[i] = sampleColumnHits(level, sampleX, centerY, sampleZ, probePos);
        }

        return new CanopyProfile(hits);
    }

    private static boolean[] sampleColumnHits(Level level, int x, int baseY, int z,
                                              BlockPos.MutableBlockPos probePos) {
        boolean[] hits = new boolean[CanopyProfile.VERTICAL_SAMPLE_COUNT];

        int minY = baseY;
        int maxY = baseY + (int) CanopyProfile.SEARCH_HEIGHT_BLOCKS;

        int clampedMin = Math.max(minY, level.getMinBuildHeight());
        int clampedMax = Math.min(maxY, level.getMaxBuildHeight() - 1);

        if (clampedMax < clampedMin) {
            return hits;
        }

        int range = clampedMax - clampedMin;

        for (int v = 0; v < CanopyProfile.VERTICAL_SAMPLE_COUNT; v++) {
            float biasedFraction = CanopyProfile.sampleFraction(v);
            int sampleY = clampedMin + Math.round(biasedFraction * range);

            probePos.set(x, sampleY, z);
            BlockState state = RedirectGuard.blockState(level, probePos);
            hits[v] = isFoliage(state);
        }

        return hits;
    }

    private static boolean isFoliage(BlockState state) {
        return state.getBlock() instanceof LeavesBlock;
    }
}
