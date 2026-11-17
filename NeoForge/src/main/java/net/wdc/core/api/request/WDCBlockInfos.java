package net.wdc.core.api.request;

import net.wdc.core.world.material.WDCFluidInfo;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;

final class WDCBlockInfos {

    private WDCBlockInfos() {}

    static WDCBlockInfo of(GlobalBlockStateRegistry registry, int stateId) {
        if (registry == null || stateId == GlobalBlockStateRegistry.NULL_ID) return WDCBlockInfo.ABSENT;
        Object cached = registry.cachedBlockInfo(stateId);
        if (cached != null) return (WDCBlockInfo) cached;
        int epoch = registry.metaEpoch();
        var key = registry.keyOf(stateId);
        var tag = registry.materialTagOf(stateId);
        WDCFluidInfo fluid = registry.fluidInfoOf(stateId);
        WDCMaterial material = WDCMaterial.from(tag.material());
        WDCBlockInfo info = new WDCBlockInfo(material != WDCMaterial.AIR, stateId, key.blockId(), material,
                fluid.present(), fluid.level());
        registry.cacheBlockInfo(stateId, epoch, info);
        return info;
    }

    static WDCWaterInfo waterOf(GlobalBlockStateRegistry registry, int stateId) {
        if (registry == null || stateId == GlobalBlockStateRegistry.NULL_ID) return WDCWaterInfo.NONE;
        Object cached = registry.cachedWaterInfo(stateId);
        if (cached != null) return (WDCWaterInfo) cached;
        int epoch = registry.metaEpoch();
        WDCFluidInfo fluid = registry.fluidInfoOf(stateId);
        if (!fluid.present()) return WDCWaterInfo.NONE;
        WDCWaterInfo water = new WDCWaterInfo(
                fluid.kind() == WDCFluidInfo.Kind.WATER,
                fluid.kind() == WDCFluidInfo.Kind.LAVA,
                true,
                fluid.source(),
                !fluid.source() && fluid.amount() > 0,
                fluid.level(),
                fluid.falling());
        registry.cacheWaterInfo(stateId, epoch, water);
        return water;
    }
}
