package net.wdc.core.world.cache;

import net.wdc.core.world.storage.WDCChunk;

public final class ServerChunkFingerprintSource implements ChunkFingerprintSource {

    @Override
    public ChunkFingerprint fingerprintOf(String dimensionId, int chunkX, int chunkZ, WDCChunk chunk) {
        if (chunk == null) {
            return ChunkFingerprint.unavailable(dimensionId, chunkX, chunkZ);
        }

        ChunkFingerprint.Builder builder = new ChunkFingerprint.Builder(dimensionId, chunkX, chunkZ);
        DefaultFingerprintSupport.populateSections(builder, chunk, DefaultFingerprintSupport.TickingCountLookup.UNAVAILABLE);
        return builder.build();
    }
}
