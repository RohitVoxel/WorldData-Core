package net.wdc.core.redirect;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class RedirectGuard {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private RedirectGuard() {}

    public static boolean isInternal() {
        return DEPTH.get()[0] > 0;
    }

    public static void enter() {
        DEPTH.get()[0]++;
    }

    public static void leave() {
        int[] depth = DEPTH.get();
        if (depth[0] > 0) depth[0]--;
    }

    public static BlockState blockState(BlockGetter level, BlockPos pos) {
        enter();
        try {
            return level.getBlockState(pos);
        } finally {
            leave();
        }
    }

    public static FluidState fluidState(BlockGetter level, BlockPos pos) {
        enter();
        try {
            return level.getFluidState(pos);
        } finally {
            leave();
        }
    }
}
