package net.wdc.core.world.storage;

public record ChunkKey(int chunkX, int chunkZ) {

    public static ChunkKey of(int blockX, int blockZ) {
        return new ChunkKey(WDCChunk.chunkXOf(blockX), WDCChunk.chunkZOf(blockZ));
    }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public long pack() {
        return pack(chunkX, chunkZ);
    }
}
