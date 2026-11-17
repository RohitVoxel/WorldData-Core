package net.wdc.core.world.cache;

import net.wdc.core.api.internal.WorldAuthority;
import net.wdc.core.world.storage.WDCChunk;

public final class SwitchingFingerprintSource implements ChunkFingerprintSource {

    private final ChunkFingerprintSource client;
    private final ChunkFingerprintSource server;

    public SwitchingFingerprintSource(ChunkFingerprintSource client, ChunkFingerprintSource server) {
        if (client == null || server == null) throw new IllegalArgumentException("sources must not be null");
        this.client = client;
        this.server = server;
    }

    @Override
    public ChunkFingerprint fingerprintOf(String dimensionId, int chunkX, int chunkZ, WDCChunk chunk) {
        return WorldAuthority.isServerAuthoritative()
                ? server.fingerprintOf(dimensionId, chunkX, chunkZ, chunk)
                : client.fingerprintOf(dimensionId, chunkX, chunkZ, chunk);
    }
}
