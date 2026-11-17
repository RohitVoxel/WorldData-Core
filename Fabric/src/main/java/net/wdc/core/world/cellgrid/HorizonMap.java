package net.wdc.core.world.cellgrid;

public final class HorizonMap {

    public static final int SECTOR_COUNT = 8;

    private static final float SECTOR_ANGLE = (float) (2.0 * Math.PI / SECTOR_COUNT);

    private final float[] sectorElevationAngles;

    public HorizonMap(float[] sectorElevationAngles) {
        if (sectorElevationAngles.length != SECTOR_COUNT) {
            throw new IllegalArgumentException(
                    "HorizonMap requires exactly " + SECTOR_COUNT + " sectors, got "
                            + sectorElevationAngles.length);
        }
        this.sectorElevationAngles = sectorElevationAngles.clone();
    }

    public float elevationAngleAt(float azimuthRadians) {
        float normalized = azimuthRadians % (float) (2.0 * Math.PI);
        if (normalized < 0f) normalized += (float) (2.0 * Math.PI);
        int sector = (int) (normalized / SECTOR_ANGLE) % SECTOR_COUNT;
        return sectorElevationAngles[sector];
    }

    public float[] sectorsView() {
        return sectorElevationAngles.clone();
    }
}
