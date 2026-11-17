package net.wdc.core.world.ingestion;

import net.wdc.core.world.material.WDCFluidInfo;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

public final class MinecraftFluidReader {

    private MinecraftFluidReader() {}

    public static WDCFluidInfo read(BlockState state) {
        if (state == null) return WDCFluidInfo.NONE;
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) return WDCFluidInfo.NONE;
        WDCFluidInfo.Kind kind = fluid.is(FluidTags.WATER) ? WDCFluidInfo.Kind.WATER
                : fluid.is(FluidTags.LAVA) ? WDCFluidInfo.Kind.LAVA : WDCFluidInfo.Kind.OTHER;
        boolean source = fluid.isSource();
        boolean falling = !source && fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING);
        return new WDCFluidInfo(kind, source, source ? 8 : fluid.getAmount(), falling);
    }
}
