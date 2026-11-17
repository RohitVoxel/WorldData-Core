package net.wdc.core.world.demand;

import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.ColumnHeights;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;
import net.wdc.core.world.storage.SizeEstimated;

public final class ChunkHeightmap implements SizeEstimated {
    public static final int UNKNOWN = Integer.MIN_VALUE;

    private final ColumnHeights heights;

    private ChunkHeightmap(ColumnHeights heights) {
        this.heights = heights;
    }

    public static ChunkHeightmap build(WDCChunk chunk, GlobalBlockStateRegistry registry) {
        if (chunk == null || registry == null) return new ChunkHeightmap(ColumnHeights.build(null, null));
        ColumnHeights published = chunk.heights();
        if (published != null && published.matches(registry)) return new ChunkHeightmap(published);
        return new ChunkHeightmap(ColumnHeights.build(chunk, registry));
    }

    public int topY(int localX, int localZ) {
        return topY(localX, localZ, HeightKind.WORLD_SURFACE);
    }

    public int topY(int localX, int localZ, HeightKind kind) {
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) {
            throw new IllegalArgumentException("local coords must be 0-15");
        }
        if (kind == null) throw new IllegalArgumentException("kind must not be null");
        int column = (localX << 4) | localZ;
        return switch (kind) {
            case WORLD_SURFACE -> heights.get(ColumnHeights.KIND_SURFACE, column);
            case MOTION_BLOCKING -> heights.get(ColumnHeights.KIND_MOTION, column);
            case OCEAN_FLOOR -> heights.get(ColumnHeights.KIND_FLOOR, column);
            case MOTION_BLOCKING_NO_LEAVES -> heights.get(ColumnHeights.KIND_NO_LEAVES, column);
        };
    }

    @Override
    public long estimatedBytes() {
        return 24L;
    }
}
