package net.wdc.core.api.request;

import net.wdc.core.world.demand.ChunkBlockIndex;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;

public final class WDCBlockIndex {

    private final ChunkBlockIndex internal;
    private final GlobalBlockStateRegistry registry;

    WDCBlockIndex(ChunkBlockIndex internal, GlobalBlockStateRegistry registry) {
        if (internal == null) throw new IllegalArgumentException("internal must not be null");
        this.internal = internal;
        this.registry = registry;
    }

    public int stateIdAt(int localX, int localZ, int blockY) {
        return internal.stateIdAt(localX, localZ, blockY);
    }

    public WDCMaterial materialAt(int localX, int localZ, int blockY) {
        int stateId = internal.stateIdAt(localX, localZ, blockY);
        if (stateId == GlobalBlockStateRegistry.NULL_ID || registry == null) return WDCMaterial.AIR;
        return WDCMaterial.from(registry.materialTagOf(stateId).material());
    }

    public boolean isPresent(int localX, int localZ, int blockY) {
        return internal.stateIdAt(localX, localZ, blockY) != GlobalBlockStateRegistry.NULL_ID;
    }

    public WDCBlockInfo blockAt(int localX, int localZ, int blockY) {
        return WDCBlockInfos.of(registry, internal.stateIdAt(localX, localZ, blockY));
    }
}
