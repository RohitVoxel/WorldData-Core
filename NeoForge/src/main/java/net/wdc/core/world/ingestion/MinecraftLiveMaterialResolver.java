package net.wdc.core.world.ingestion;

import net.wdc.core.world.material.WDCMaterial;
import net.wdc.core.world.material.WDCMaterialTag;
import net.wdc.core.world.material.LiveMaterialResolver;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class MinecraftLiveMaterialResolver implements LiveMaterialResolver {

    private static final TagKey<Block> ORES = TagKey.create(Registries.BLOCK, ResourceLocation.parse("c:ores"));

    @Override
    public WDCMaterialTag resolve(BlockState state) {
        if (state == null) return WDCMaterialTag.of(WDCMaterial.UNKNOWN);

        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty() && state.getBlock() instanceof LiquidBlock) {
            if (fluidState.is(FluidTags.WATER)) return WDCMaterialTag.of(WDCMaterial.WATER);
            if (fluidState.is(FluidTags.LAVA)) return WDCMaterialTag.of(WDCMaterial.LAVA);
            return WDCMaterialTag.unclassifiedFluid();
        }

        Block block = state.getBlock();
        if (block.builtInRegistryHolder().is(ORES)) return WDCMaterialTag.of(WDCMaterial.STONE);
        if (block.builtInRegistryHolder().is(BlockTags.LOGS) || block.builtInRegistryHolder().is(BlockTags.PLANKS)) {
            return WDCMaterialTag.of(WDCMaterial.WOOD);
        }
        if (block.builtInRegistryHolder().is(BlockTags.LEAVES)) return WDCMaterialTag.of(WDCMaterial.LEAVES);

        return WDCMaterialTag.of(WDCMaterial.UNKNOWN);
    }
}
