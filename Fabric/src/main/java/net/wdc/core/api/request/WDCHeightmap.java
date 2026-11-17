package net.wdc.core.api.request;

import net.wdc.core.world.demand.ChunkHeightmap;
import net.wdc.core.world.demand.HeightKind;

public final class WDCHeightmap {

    public static final int UNKNOWN = ChunkHeightmap.UNKNOWN;

    private static final HeightKind SURFACE = WDCHeightKind.WORLD_SURFACE.toInternal();

    private final ChunkHeightmap internal;

    WDCHeightmap(ChunkHeightmap internal) {
        if (internal == null) throw new IllegalArgumentException("internal must not be null");
        this.internal = internal;
    }

    public int topY(int localX, int localZ) {
        return internal.topY(localX, localZ, SURFACE);
    }

    public int topY(int localX, int localZ, WDCHeightKind kind) {
        if (kind == null) throw new IllegalArgumentException("kind must not be null");
        return internal.topY(localX, localZ, kind.toInternal());
    }
}
