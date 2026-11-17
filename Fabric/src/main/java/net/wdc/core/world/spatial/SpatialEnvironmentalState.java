package net.wdc.core.world.spatial;

public record SpatialEnvironmentalState(
        float elevation,
        float slopeRadians,
        float aspectRadians,
        Landform landform,
        float localRelief,
        float enclosure,
        float exposure,
        DirectionalExposure directionalExposure,
        WaterProximity waterProximity
) {
    public static SpatialEnvironmentalState fromProfile(SpatialProfile profile) {
        return new SpatialEnvironmentalState(
                profile.elevation(), profile.slopeRadians(), profile.aspectRadians(), profile.landform(),
                profile.localRelief(), profile.enclosure(), profile.exposure(),
                profile.directionalExposure(), profile.waterProximity());
    }

    public static SpatialEnvironmentalState neutral() {
        DirectionalExposure openSky = new DirectionalExposure(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f);
        return new SpatialEnvironmentalState(
                64f, 0f, 0f, Landform.FLAT, 0f, 0f, 1f, openSky, WaterProximity.NONE);
    }
}
