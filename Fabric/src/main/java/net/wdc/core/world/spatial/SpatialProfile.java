package net.wdc.core.world.spatial;

public record SpatialProfile(
        float elevation,
        float slopeRadians,
        float aspectRadians,
        Landform landform,
        float localRelief,
        float surroundingMinElevation,
        float surroundingMaxElevation,
        float enclosure,
        float exposure,
        DirectionalExposure directionalExposure,
        WaterProximity waterProximity
) {
    public SpatialProfile {
        slopeRadians = Math.clamp(slopeRadians, 0f, (float) (Math.PI / 2.0));
        enclosure = Math.clamp(enclosure, 0f, 1f);
        exposure = Math.clamp(exposure, 0f, 1f);
        if (landform == null) throw new IllegalArgumentException("landform must not be null");
        if (directionalExposure == null) throw new IllegalArgumentException("directionalExposure must not be null");
        if (waterProximity == null) throw new IllegalArgumentException("waterProximity must not be null");
    }

    private static final SpatialProfile NEUTRAL = new SpatialProfile(
            64f, 0f, 0f, Landform.FLAT, 0f, 64f, 64f, 0f, 1f,
            new DirectionalExposure(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f), WaterProximity.NONE);

    public static SpatialProfile neutral() {
        return NEUTRAL;
    }
}
