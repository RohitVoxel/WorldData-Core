package net.wdc.core.quality.memory;

public final class MemoryWeights {

    private MemoryWeights() {}

    public static final float CELL_HUMIDITY_MEMORY_DEFAULT = 0.5f;
    public static final float CELL_HUMIDITY_MEMORY_ACCEL = 0.6f;
    public static final float CELL_HUMIDITY_MEMORY_DAMP = 0.85f;

    public static final float CELL_STORM_INFLUENCE_DEFAULT = 0.0f;
    public static final float CELL_STORM_INFLUENCE_ACCEL = 0.8f;
    public static final float CELL_STORM_INFLUENCE_DAMP = 0.9f;
}
