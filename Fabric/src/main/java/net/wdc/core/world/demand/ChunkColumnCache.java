package net.wdc.core.world.demand;

import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.ColumnHeights;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;
import net.wdc.core.world.storage.SizeEstimated;

public final class ChunkColumnCache implements SizeEstimated {
    public static final int UNKNOWN = Integer.MIN_VALUE;

    private final ColumnHeights heights;
    private final WDCChunk chunk;

    private ChunkColumnCache(ColumnHeights heights, WDCChunk chunk) {
        this.heights = heights;
        this.chunk = chunk;
    }

    public static ChunkColumnCache build(WDCChunk chunk, GlobalBlockStateRegistry registry) {
        if (chunk == null || registry == null) return new ChunkColumnCache(ColumnHeights.build(null, null), null);
        ColumnHeights published = chunk.heights();
        if (published != null && published.matches(registry)) return new ChunkColumnCache(published, chunk);
        return new ChunkColumnCache(ColumnHeights.build(chunk, registry), chunk);
    }

    public int topSolidY(int localX, int localZ) {
        validate(localX, localZ);
        return heights.get(ColumnHeights.KIND_SOLID, (localX << 4) | localZ);
    }

    public int topWaterY(int localX, int localZ) {
        validate(localX, localZ);
        return heights.get(ColumnHeights.KIND_WATER, (localX << 4) | localZ);
    }

    public int surfaceStateId(int localX, int localZ) {
        validate(localX, localZ);
        int solid = heights.get(ColumnHeights.KIND_SOLID, (localX << 4) | localZ);
        if (solid == UNKNOWN || chunk == null) return GlobalBlockStateRegistry.NULL_ID;
        return chunk.getBlock(localX, solid, localZ);
    }

    public boolean isWaterColumn(int localX, int localZ) {
        validate(localX, localZ);
        int column = (localX << 4) | localZ;
        int water = heights.get(ColumnHeights.KIND_WATER, column);
        if (water == UNKNOWN) return false;
        int solid = heights.get(ColumnHeights.KIND_SOLID, column);
        return solid == UNKNOWN || water > solid;
    }

    private static void validate(int localX, int localZ) {
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) {
            throw new IllegalArgumentException("local coords must be 0-15");
        }
    }

    @Override
    public long estimatedBytes() {
        return 24L;
    }
}
