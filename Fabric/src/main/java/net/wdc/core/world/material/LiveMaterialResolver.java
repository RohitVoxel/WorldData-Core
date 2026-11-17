package net.wdc.core.world.material;

import net.minecraft.world.level.block.state.BlockState;

public interface LiveMaterialResolver {
    WDCMaterialTag resolve(BlockState state);
}
