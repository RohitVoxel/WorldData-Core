package net.wdc.core.world.cellgrid;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

final class HorizonMapGenerator {

    private HorizonMapGenerator() {}

    private static final float[] SAMPLE_DISTANCES = {8f, 16f, 32f};

    static HorizonMap generate(CellCoord coord, int cellSize, Level level) {
        int centerX = coord.centerWorldX(cellSize);
        int centerY = coord.centerWorldY(cellSize);
        int centerZ = coord.centerWorldZ(cellSize);

        float[] sectors = new float[HorizonMap.SECTOR_COUNT];

        for (int s = 0; s < HorizonMap.SECTOR_COUNT; s++) {
            float azimuth = (float) (s * (2.0 * Math.PI / HorizonMap.SECTOR_COUNT));
            float dirX = (float) Math.cos(azimuth);
            float dirZ = (float) Math.sin(azimuth);

            float maxElevation = -(float) (Math.PI / 2.0);

            for (float distance : SAMPLE_DISTANCES) {
                int sampleX = centerX + Math.round(dirX * distance);
                int sampleZ = centerZ + Math.round(dirZ * distance);

                int terrainHeight = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sampleX, sampleZ);

                float heightDelta = terrainHeight - centerY;
                float elevation   = (float) Math.atan2(heightDelta, distance);

                if (elevation > maxElevation) {
                    maxElevation = elevation;
                }
            }

            sectors[s] = maxElevation;
        }

        return new HorizonMap(sectors);
    }
}
