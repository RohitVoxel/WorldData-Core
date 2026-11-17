package net.wdc.core.world.storage;

import net.wdc.core.world.material.WDCFluidInfo;
import net.wdc.core.world.material.WDCMaterial;
import net.wdc.core.world.material.WDCMaterialClassifier;
import net.wdc.core.world.material.WDCFluidState;
import net.wdc.core.world.material.WDCMaterialTag;

public record StateMeta(int flags, WDCMaterialTag material, WDCFluidInfo fluid, int tagMask, int lightEmission, int lightOpacity) {

    public StateMeta {
        if (material == null) throw new IllegalArgumentException("material must not be null");
        if (fluid == null) fluid = WDCFluidInfo.NONE;
        flags &= BlockToken.FLAG_MASK;
        lightEmission = Math.max(0, Math.min(15, lightEmission));
        lightOpacity = Math.max(0, Math.min(15, lightOpacity));
    }

    public static StateMeta airOf(BlockStateKey key) {
        int flags = key.blockId().startsWith("minecraft:") ? 0 : BlockToken.FLAG_CUSTOM_MATERIAL;
        int tags = StateTags.fromMaterial(WDCMaterial.AIR);
        if (!key.blockId().startsWith("minecraft:")) tags |= StateTags.MODDED;
        return new StateMeta(flags, WDCMaterialTag.of(WDCMaterial.AIR), WDCFluidInfo.NONE, tags, 0, 0);
    }

    public static StateMeta fromKey(BlockStateKey key) {
        WDCMaterial material = WDCMaterialClassifier.classify(key.blockId());
        WDCFluidInfo fluid = WDCFluidState.infoOf(key);
        int flags = 0;
        if (fluid.present()) flags |= BlockToken.FLAG_HAS_FLUID;
        boolean blocksMotion = StateTags.blocksMotion(key.blockId(), material);
        if (blocksMotion || fluid.present()) flags |= BlockToken.FLAG_MOTION_BLOCKING;
        if (material == WDCMaterial.LEAVES) flags |= BlockToken.FLAG_CANOPY;
        if (!key.blockId().startsWith("minecraft:")) flags |= BlockToken.FLAG_CUSTOM_MATERIAL;
        int tags = StateTags.fromMaterial(material);
        if (blocksMotion) tags |= StateTags.BLOCKS_MOTION;
        if (!key.blockId().startsWith("minecraft:")) tags |= StateTags.MODDED;
        return new StateMeta(flags, WDCMaterialTag.of(material), fluid, tags, 0, 0);
    }
}
