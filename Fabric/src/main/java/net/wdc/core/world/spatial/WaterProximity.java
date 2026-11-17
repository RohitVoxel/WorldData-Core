package net.wdc.core.world.spatial;

public record WaterProximity(
        boolean present,
        float nearestDistanceBlocks,
        float influenceStrength,
        float directionRadians
) {
    public static final WaterProximity NONE = new WaterProximity(false, Float.MAX_VALUE, 0f, 0f);

    public WaterProximity {
        influenceStrength = Math.clamp(influenceStrength, 0f, 1f);
    }
}
