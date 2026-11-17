package net.wdc.core.world.demand;

public final class QueryScope {
    public static final long MAX_CHUNK_COUNT = 1_000_000L;

    private final String dimensionId;
    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX;
    private final int maxChunkZ;

    private QueryScope(String dimensionId, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        if (dimensionId == null || dimensionId.isEmpty()) {
            throw new IllegalArgumentException("dimensionId must not be null or empty");
        }
        this.dimensionId = dimensionId;
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
    }

    public static QueryScope rectangle(String dimensionId, int chunkX1, int chunkZ1, int chunkX2, int chunkZ2) {
        if (dimensionId == null || dimensionId.isEmpty()) {
            throw new IllegalArgumentException("dimensionId must not be null or empty");
        }
        int minX = Math.min(chunkX1, chunkX2);
        int minZ = Math.min(chunkZ1, chunkZ2);
        int maxX = Math.max(chunkX1, chunkX2);
        int maxZ = Math.max(chunkZ1, chunkZ2);
        long count = chunkCount(minX, minZ, maxX, maxZ);
        if (count > MAX_CHUNK_COUNT) {
            throw new IllegalArgumentException("rectangle spans " + count + " chunks, exceeding the max of "
                    + MAX_CHUNK_COUNT + " for a single scope; split it into smaller requests");
        }
        return new QueryScope(dimensionId, minX, minZ, maxX, maxZ);
    }

    public static QueryScope single(String dimensionId, int chunkX, int chunkZ) {
        return new QueryScope(dimensionId, chunkX, chunkZ, chunkX, chunkZ);
    }

    public String dimensionId() { return dimensionId; }
    public int minChunkX() { return minChunkX; }
    public int minChunkZ() { return minChunkZ; }
    public int maxChunkX() { return maxChunkX; }
    public int maxChunkZ() { return maxChunkZ; }

    public boolean contains(int chunkX, int chunkZ) {
        return chunkX >= minChunkX && chunkX <= maxChunkX
                && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
    }

    public long chunkCount() {
        return chunkCount(minChunkX, minChunkZ, maxChunkX, maxChunkZ);
    }

    private static long chunkCount(int minX, int minZ, int maxX, int maxZ) {
        long width = (long) maxX - (long) minX + 1L;
        long height = (long) maxZ - (long) minZ + 1L;
        if (width > MAX_CHUNK_COUNT || height > MAX_CHUNK_COUNT) return Long.MAX_VALUE;
        return width * height;
    }
}
