package net.wdc.core.world.cellgrid;

import net.wdc.core.quality.memory.AtmosphereDrifter;
import net.wdc.core.math.WDCMath;
import net.wdc.core.quality.memory.MemoryWeights;
import net.wdc.core.world.spatial.SpatialProfile;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

public final class WDCCell {

    private final CellCoord coord;
    private final long deterministicSeed;

    private Holder<Biome> biome;
    private boolean skyExposed;
    private HorizonMap horizonMap;
    private CanopyProfile canopyProfile;
    private SpatialProfile spatialProfile;

    private boolean dirty = false;

    private long lastTouchedTick;

    private final AtmosphereDrifter humidityMemoryDrifter = new AtmosphereDrifter(
            MemoryWeights.CELL_HUMIDITY_MEMORY_DEFAULT,
            MemoryWeights.CELL_HUMIDITY_MEMORY_ACCEL,
            MemoryWeights.CELL_HUMIDITY_MEMORY_DAMP);

    private final AtmosphereDrifter stormInfluenceDrifter = new AtmosphereDrifter(
            MemoryWeights.CELL_STORM_INFLUENCE_DEFAULT,
            MemoryWeights.CELL_STORM_INFLUENCE_ACCEL,
            MemoryWeights.CELL_STORM_INFLUENCE_DAMP);

    private float humidityMemory       = MemoryWeights.CELL_HUMIDITY_MEMORY_DEFAULT;
    private float stormInfluence       = MemoryWeights.CELL_STORM_INFLUENCE_DEFAULT;
    private long  lastMemoryUpdateTick = -1L;

    WDCCell(CellCoord coord, long deterministicSeed, Holder<Biome> biome,
              boolean skyExposed, HorizonMap horizonMap, CanopyProfile canopyProfile,
              SpatialProfile spatialProfile, long creationTick) {
        this.coord             = coord;
        this.deterministicSeed = deterministicSeed;
        this.biome             = biome;
        this.skyExposed        = skyExposed;
        this.horizonMap        = horizonMap;
        this.canopyProfile     = canopyProfile;
        this.spatialProfile    = spatialProfile;
        this.lastTouchedTick   = creationTick;
    }

    public CellCoord coord()        { return coord; }
    public long deterministicSeed() { return deterministicSeed; }
    public Holder<Biome> biome()    { return biome; }
    public boolean skyExposed()     { return skyExposed; }

    public HorizonMap horizonMap()  { return horizonMap; }

    public CanopyProfile canopyProfile() { return canopyProfile; }

    public SpatialProfile spatialProfile() { return spatialProfile; }

    public boolean isDirty()        { return dirty; }
    public long lastTouchedTick()   { return lastTouchedTick; }

    public float humidityMemory()       { return humidityMemory; }
    public float stormInfluence()       { return stormInfluence; }
    public long  lastMemoryUpdateTick() { return lastMemoryUpdateTick; }

    public void advanceMemory(float humidityTarget, float stormTarget, float deltaSec, long tick) {
        humidityMemory = WDCMath.clamp(humidityMemoryDrifter.advance(humidityTarget, deltaSec), 0f, 1f);
        stormInfluence = WDCMath.clamp(stormInfluenceDrifter.advance(stormTarget, deltaSec), 0f, 1f);
        lastMemoryUpdateTick = tick;
    }

    void absorbLoadedMemory(float humidityMemory, float stormInfluence, long tick) {
        this.humidityMemory = WDCMath.clamp(humidityMemory, 0f, 1f);
        this.stormInfluence = WDCMath.clamp(stormInfluence, 0f, 1f);
        this.humidityMemoryDrifter.snap(this.humidityMemory);
        this.stormInfluenceDrifter.snap(this.stormInfluence);
        this.lastMemoryUpdateTick = tick;
    }

    void touch(long tick) {
        this.lastTouchedTick = tick;
    }

    void markDirty() {
        this.dirty = true;
    }

    void applyRegeneration(Holder<Biome> biome, boolean skyExposed, HorizonMap horizonMap,
                           CanopyProfile canopyProfile, SpatialProfile spatialProfile) {
        this.biome          = biome;
        this.skyExposed     = skyExposed;
        this.horizonMap     = horizonMap;
        this.canopyProfile  = canopyProfile;
        this.spatialProfile = spatialProfile;
        this.dirty          = false;
    }
}
