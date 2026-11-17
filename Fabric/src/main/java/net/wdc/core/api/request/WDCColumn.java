package net.wdc.core.api.request;

import net.wdc.core.world.demand.ChunkColumnCache;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;

public final class WDCColumn {

    public static final int UNKNOWN = ChunkColumnCache.UNKNOWN;

    private final ChunkColumnCache internal;
    private final GlobalBlockStateRegistry registry;

    WDCColumn(ChunkColumnCache internal, GlobalBlockStateRegistry registry) {
        if (internal == null) throw new IllegalArgumentException("internal must not be null");
        this.internal = internal;
        this.registry = registry;
    }

    public int topSolidY(int localX, int localZ) {
        return internal.topSolidY(localX, localZ);
    }

    public int topWaterY(int localX, int localZ) {
        return internal.topWaterY(localX, localZ);
    }

    public boolean isWaterColumn(int localX, int localZ) {
        return internal.isWaterColumn(localX, localZ);
    }

    public WDCMaterial surfaceMaterial(int localX, int localZ) {
        int stateId = internal.surfaceStateId(localX, localZ);
        if (stateId == GlobalBlockStateRegistry.NULL_ID || registry == null) return WDCMaterial.UNKNOWN;
        return WDCMaterial.from(registry.materialTagOf(stateId).material());
    }
}
