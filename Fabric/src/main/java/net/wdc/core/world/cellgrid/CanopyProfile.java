package net.wdc.core.world.cellgrid;

public final class CanopyProfile {

    public static final int SAMPLE_COUNT = 5;

    public static final int VERTICAL_SAMPLE_COUNT = 8;

    public static final float SEARCH_HEIGHT_BLOCKS = 25.0f;

    private final boolean[][] foliageHits;

    public CanopyProfile(boolean[][] foliageHits) {
        if (foliageHits.length != SAMPLE_COUNT) {
            throw new IllegalArgumentException(
                    "CanopyProfile requires exactly " + SAMPLE_COUNT + " columns, got "
                            + foliageHits.length);
        }
        boolean[][] copy = new boolean[SAMPLE_COUNT][];
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            if (foliageHits[i].length != VERTICAL_SAMPLE_COUNT) {
                throw new IllegalArgumentException(
                        "CanopyProfile column " + i + " requires exactly "
                                + VERTICAL_SAMPLE_COUNT + " vertical samples, got "
                                + foliageHits[i].length);
            }
            copy[i] = foliageHits[i].clone();
        }
        this.foliageHits = copy;
    }

    public boolean[][] hitsView() {
        boolean[][] copy = new boolean[foliageHits.length][];
        for (int i = 0; i < foliageHits.length; i++) {
            copy[i] = foliageHits[i].clone();
        }
        return copy;
    }

    public static float sampleFraction(int sampleIndex) {
        if (VERTICAL_SAMPLE_COUNT <= 1) return 0f;
        float linearFraction = (float) sampleIndex / (VERTICAL_SAMPLE_COUNT - 1);
        return linearFraction * linearFraction;
    }
}
