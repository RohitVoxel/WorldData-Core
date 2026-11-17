package net.wdc.core.api.coords;

public final class CoordsApi {

    public int blockToChunk(int blockCoord) { return blockCoord >> 4; }

    public int blockToSection(int blockY) { return blockY >> 4; }

    public int blockToLocal(int blockCoord) { return blockCoord & 15; }

    public int chunkToMinBlock(int chunkCoord) { return chunkCoord << 4; }

    public int chunkToMaxBlock(int chunkCoord) { return (chunkCoord << 4) + 15; }

    public long packChunk(int chunkX, int chunkZ) {
        return (((long) chunkX) & 0xFFFFFFFFL) | ((((long) chunkZ) & 0xFFFFFFFFL) << 32);
    }

    public int unpackChunkX(long packed) { return (int) (packed & 0xFFFFFFFFL); }

    public int unpackChunkZ(long packed) { return (int) (packed >>> 32); }

    public long chunkDistanceSquared(int chunkX1, int chunkZ1, int chunkX2, int chunkZ2) {
        long dx = chunkX1 - chunkX2;
        long dz = chunkZ1 - chunkZ2;
        return dx * dx + dz * dz;
    }
}
