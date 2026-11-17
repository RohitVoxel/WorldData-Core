package net.wdc.core.world.change;

@FunctionalInterface
public interface ChunkRebuilder {

    ChunkRebuilder NONE = (dimensionId, chunkX, chunkZ) -> 0;

    int rebuild(String dimensionId, int chunkX, int chunkZ);
}
