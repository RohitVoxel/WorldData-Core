package net.wdc.core.api.spatial;

abstract class SpatialRegion {

    final int minX;
    final int minY;
    final int minZ;
    final int maxX;
    final int maxY;
    final int maxZ;

    SpatialRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    abstract boolean contains(int x, int y, int z);

    boolean isBox() {
        return false;
    }

    boolean isSphere() {
        return false;
    }

    int rowHalfWidth(int worldY, int worldZ) {
        return -1;
    }

    int centerX() {
        return 0;
    }

    byte[] columnTable(int chunkX, int chunkZ) {
        byte[] table = new byte[256];
        int x0 = chunkX << 4;
        int z0 = chunkZ << 4;
        int xlo = Math.max(0, minX - x0);
        int xhi = Math.min(15, maxX - x0);
        int zlo = Math.max(0, minZ - z0);
        int zhi = Math.min(15, maxZ - z0);
        for (int z = zlo; z <= zhi; z++) {
            for (int x = xlo; x <= xhi; x++) table[(z << 4) | x] = 1;
        }
        return table;
    }

    boolean coversColumns(int chunkX, int chunkZ) {
        int x0 = chunkX << 4;
        int z0 = chunkZ << 4;
        return minX <= x0 && maxX >= x0 + 15 && minZ <= z0 && maxZ >= z0 + 15;
    }

    static int lowerBound(char[] sorted, int size, int key) {
        int lo = 0;
        int hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < key) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    abstract boolean containsCube(int x0, int y0, int z0, int size);

    abstract long voxelsInChunk(int chunkX, int chunkZ);

    int minChunkX() {
        return minX >> 4;
    }

    int maxChunkX() {
        return maxX >> 4;
    }

    int minChunkZ() {
        return minZ >> 4;
    }

    int maxChunkZ() {
        return maxZ >> 4;
    }

    long chunkCount() {
        return (long) (maxChunkX() - minChunkX() + 1) * (maxChunkZ() - minChunkZ() + 1);
    }

    static SpatialRegion box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    static SpatialRegion sphere(int cx, int cy, int cz, int radius) {
        return new Sphere(cx, cy, cz, radius);
    }

    private static final class Box extends SpatialRegion {

        Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            super(minX, minY, minZ, maxX, maxY, maxZ);
        }

        @Override
        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        @Override
        boolean isBox() {
            return true;
        }

        @Override
        boolean containsCube(int x0, int y0, int z0, int size) {
            return x0 >= minX && x0 + size - 1 <= maxX
                    && y0 >= minY && y0 + size - 1 <= maxY
                    && z0 >= minZ && z0 + size - 1 <= maxZ;
        }

        @Override
        long voxelsInChunk(int chunkX, int chunkZ) {
            long dx = Math.min(maxX, (chunkX << 4) + 15) - Math.max(minX, chunkX << 4) + 1L;
            long dz = Math.min(maxZ, (chunkZ << 4) + 15) - Math.max(minZ, chunkZ << 4) + 1L;
            if (dx <= 0L || dz <= 0L) return 0L;
            return dx * dz * ((long) maxY - minY + 1L);
        }
    }

    private static final class Sphere extends SpatialRegion {

        private final int cx;
        private final int cy;
        private final int cz;
        private final long radiusSquared;

        Sphere(int cx, int cy, int cz, int radius) {
            super(cx - radius, cy - radius, cz - radius, cx + radius, cy + radius, cz + radius);
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.radiusSquared = (long) radius * radius;
        }

        @Override
        boolean isSphere() {
            return true;
        }

        @Override
        int centerX() {
            return cx;
        }

        @Override
        int rowHalfWidth(int worldY, int worldZ) {
            long dy = worldY - (long) cy;
            long dz = worldZ - (long) cz;
            long rem = radiusSquared - dy * dy - dz * dz;
            if (rem < 0L) return -1;
            int half = (int) Math.floor(Math.sqrt((double) rem));
            while ((long) (half + 1) * (half + 1) <= rem) half++;
            while ((long) half * half > rem) half--;
            return half;
        }

        @Override
        boolean contains(int x, int y, int z) {
            long dx = x - (long) cx;
            long dy = y - (long) cy;
            long dz = z - (long) cz;
            return dx * dx + dy * dy + dz * dz <= radiusSquared;
        }

        @Override
        boolean containsCube(int x0, int y0, int z0, int size) {
            long dx = Math.max(Math.abs(x0 - (long) cx), Math.abs(x0 + size - 1 - (long) cx));
            long dy = Math.max(Math.abs(y0 - (long) cy), Math.abs(y0 + size - 1 - (long) cy));
            long dz = Math.max(Math.abs(z0 - (long) cz), Math.abs(z0 + size - 1 - (long) cz));
            return dx * dx + dy * dy + dz * dz <= radiusSquared;
        }

        @Override
        long voxelsInChunk(int chunkX, int chunkZ) {
            int chunkMinX = chunkX << 4;
            int chunkMaxX = chunkMinX + 15;
            int zFrom = Math.max(minZ, chunkZ << 4);
            int zTo = Math.min(maxZ, (chunkZ << 4) + 15);
            long total = 0L;
            for (int y = minY; y <= maxY; y++) {
                for (int z = zFrom; z <= zTo; z++) {
                    int half = rowHalfWidth(y, z);
                    if (half < 0) continue;
                    int from = Math.max(cx - half, chunkMinX);
                    int to = Math.min(cx + half, chunkMaxX);
                    if (to >= from) total += to - from + 1L;
                }
            }
            return total;
        }
    }
}
