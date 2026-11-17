package net.wdc.core.world.spatial;

public record DirectionalExposure(
        float east, float southeast, float south, float southwest,
        float west, float northwest, float north, float northeast
) {
    public float average() {
        return (north + northeast + east + southeast + south + southwest + west + northwest) / 8f;
    }

    public float towards(float azimuthRadians) {
        float[] values = {east, southeast, south, southwest, west, northwest, north, northeast};
        float normalized = azimuthRadians % (float) (2.0 * Math.PI);
        if (normalized < 0f) normalized += (float) (2.0 * Math.PI);

        float sectorWidth = (float) (Math.PI / 4);
        int lower = (int) (normalized / sectorWidth) % 8;
        int upper = (lower + 1) % 8;
        float t = (normalized - lower * sectorWidth) / sectorWidth;

        return values[lower] * (1f - t) + values[upper] * t;
    }
}
