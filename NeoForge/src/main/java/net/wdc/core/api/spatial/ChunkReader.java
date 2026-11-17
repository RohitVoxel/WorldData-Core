package net.wdc.core.api.spatial;

import net.wdc.core.api.request.WDCChunkResult;

@FunctionalInterface
public interface ChunkReader {

    WDCChunkResult read(String dimensionId, int chunkX, int chunkZ);
}
