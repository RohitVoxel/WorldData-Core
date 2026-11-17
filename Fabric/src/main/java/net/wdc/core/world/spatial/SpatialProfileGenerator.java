package net.wdc.core.world.spatial;

import net.wdc.core.world.cellgrid.CellCoord;
import net.wdc.core.redirect.RedirectGuard;
import net.wdc.core.world.cellgrid.HorizonMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

public final class SpatialProfileGenerator {

    private SpatialProfileGenerator() {}

    private static final int GRADIENT_OFFSET = 4;

    private static final float[] RELIEF_DISTANCES = {8f, 16f, 32f};
    private static final int RELIEF_DIRECTIONS = 8;

    private static final int WATER_SEARCH_RADIUS = 24;
    private static final int WATER_RING_STEP = 4;

    private static final float RIDGE_RELIEF_THRESHOLD = 6f;
    private static final float VALLEY_RELIEF_THRESHOLD = 6f;
    private static final float VALLEY_ENCLOSURE_THRESHOLD = 0.55f;
    private static final float FLAT_SLOPE_THRESHOLD = 0.09f;
    private static final float FLAT_RELIEF_THRESHOLD = 3.5f;
    private static final float DEPRESSION_DELTA_THRESHOLD = 4f;

    public static SpatialProfile generate(CellCoord coord, int cellSize, Level level, HorizonMap horizonMap) {
        int centerX = coord.centerWorldX(cellSize);
        int centerZ = coord.centerWorldZ(cellSize);

        float elevation = level.getHeight(Heightmap.Types.WORLD_SURFACE, centerX, centerZ);

        float north = level.getHeight(Heightmap.Types.WORLD_SURFACE, centerX, centerZ - GRADIENT_OFFSET);
        float south = level.getHeight(Heightmap.Types.WORLD_SURFACE, centerX, centerZ + GRADIENT_OFFSET);
        float east  = level.getHeight(Heightmap.Types.WORLD_SURFACE, centerX + GRADIENT_OFFSET, centerZ);
        float west  = level.getHeight(Heightmap.Types.WORLD_SURFACE, centerX - GRADIENT_OFFSET, centerZ);

        float dzdx = (east - west) / (2f * GRADIENT_OFFSET);
        float dzdy = (south - north) / (2f * GRADIENT_OFFSET);

        float slopeRadians = (float) Math.atan(Math.sqrt(dzdx * dzdx + dzdy * dzdy));

        float aspectRadians = (dzdx == 0f && dzdy == 0f) ? 0f : (float) Math.atan2(dzdy, dzdx);
        if (aspectRadians < 0f) aspectRadians += (float) (2.0 * Math.PI);

        ReliefSample relief = sampleRelief(centerX, centerZ, elevation, level);
        DirectionalExposure directionalExposure = fromHorizonMap(horizonMap);
        float exposure = directionalExposure.average();
        float enclosure = 1f - exposure;

        Landform landform = classify(elevation, relief, slopeRadians, enclosure);
        WaterProximity water = sampleWaterProximity(centerX, centerZ, level);

        return new SpatialProfile(
                elevation, slopeRadians, aspectRadians, landform,
                relief.localRelief(), relief.minElevation(), relief.maxElevation(),
                enclosure, exposure, directionalExposure, water);
    }

    private record ReliefSample(float minElevation, float maxElevation, float localRelief) {}

    private static ReliefSample sampleRelief(int centerX, int centerZ, float centerElevation, Level level) {
        float min = centerElevation;
        float max = centerElevation;

        for (int d = 0; d < RELIEF_DIRECTIONS; d++) {
            float azimuth = (float) (d * (2.0 * Math.PI / RELIEF_DIRECTIONS));
            float dirX = (float) Math.cos(azimuth);
            float dirZ = (float) Math.sin(azimuth);

            for (float distance : RELIEF_DISTANCES) {
                int sampleX = centerX + Math.round(dirX * distance);
                int sampleZ = centerZ + Math.round(dirZ * distance);
                float height = level.getHeight(Heightmap.Types.WORLD_SURFACE, sampleX, sampleZ);
                if (height < min) min = height;
                if (height > max) max = height;
            }
        }

        return new ReliefSample(min, max, max - min);
    }

    private static DirectionalExposure fromHorizonMap(HorizonMap horizonMap) {
        float[] sectors = horizonMap.sectorsView();
        float[] openness = new float[sectors.length];
        for (int i = 0; i < sectors.length; i++) {
            openness[i] = 1f - Math.clamp(sectors[i] / (float) (Math.PI / 2.0), 0f, 1f);
        }
        return new DirectionalExposure(
                openness[0], openness[1], openness[2], openness[3],
                openness[4], openness[5], openness[6], openness[7]);
    }

    private static Landform classify(float elevation, ReliefSample relief, float slopeRadians, float enclosure) {
        float meanSurrounding = (relief.minElevation() + relief.maxElevation()) / 2f;
        float deltaFromMean = elevation - meanSurrounding;

        if (relief.localRelief() < FLAT_RELIEF_THRESHOLD && slopeRadians < FLAT_SLOPE_THRESHOLD) {
            return Landform.FLAT;
        }
        if (elevation >= relief.maxElevation() - 1f && relief.localRelief() >= RIDGE_RELIEF_THRESHOLD) {
            return Landform.RIDGE;
        }
        if (elevation <= relief.minElevation() + DEPRESSION_DELTA_THRESHOLD
                && deltaFromMean < -DEPRESSION_DELTA_THRESHOLD && relief.localRelief() >= VALLEY_RELIEF_THRESHOLD) {
            return (enclosure >= VALLEY_ENCLOSURE_THRESHOLD) ? Landform.VALLEY : Landform.DEPRESSION;
        }
        if (deltaFromMean > relief.localRelief() * 0.2f) return Landform.UPPER_SLOPE;
        if (deltaFromMean < -relief.localRelief() * 0.2f) return Landform.LOWER_SLOPE;
        return Landform.MID_SLOPE;
    }

    private static WaterProximity sampleWaterProximity(int centerX, int centerZ, Level level) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

        for (int radius = 0; radius <= WATER_SEARCH_RADIUS; radius += WATER_RING_STEP) {
            int hitDx = 0;
            int hitDz = 0;
            boolean found = false;
            float bestDistSq = Float.MAX_VALUE;

            for (int dx = -radius; dx <= radius; dx += Math.max(1, WATER_RING_STEP / 2)) {
                for (int dz = -radius; dz <= radius; dz += Math.max(1, WATER_RING_STEP / 2)) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;

                    int sampleX = centerX + dx;
                    int sampleZ = centerZ + dz;
                    int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, sampleX, sampleZ);
                    probe.set(sampleX, surfaceY - 1, sampleZ);

                    if (RedirectGuard.fluidState(level, probe).is(FluidTags.WATER)) {
                        float distSq = dx * dx + dz * dz;
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            hitDx = dx;
                            hitDz = dz;
                            found = true;
                        }
                    }
                }
            }

            if (found) {
                float distance = (float) Math.sqrt(bestDistSq);
                float strength = 1f - Math.clamp(distance / WATER_SEARCH_RADIUS, 0f, 1f);
                float direction = (float) Math.atan2(hitDz, hitDx);
                if (direction < 0f) direction += (float) (2.0 * Math.PI);
                return new WaterProximity(true, distance, strength, direction);
            }
        }

        return WaterProximity.NONE;
    }
}
