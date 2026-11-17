package net.wdc.core.world.ingestion;

public interface MinecraftChunkView {

    String dimensionId();

    int chunkX();

    int chunkZ();

    int sectionCount();

    int sectionYAt(int index);

    MinecraftSectionView sectionAt(int index);

    default BiomeSample biomeSample() {
        return null;
    }

    record BiomeSample(String biomeId, float rawTemperature, boolean hasPrecipitation) {
    }
}
