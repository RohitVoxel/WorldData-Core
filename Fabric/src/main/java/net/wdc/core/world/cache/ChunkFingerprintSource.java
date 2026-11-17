package net.wdc.core.world.cache;

import net.wdc.core.world.storage.WDCChunk;

public interface ChunkFingerprintSource {

    ChunkFingerprint fingerprintOf(String dimensionId, int chunkX, int chunkZ, WDCChunk chunk);
}
