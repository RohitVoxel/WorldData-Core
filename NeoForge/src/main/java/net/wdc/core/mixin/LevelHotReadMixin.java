package net.wdc.core.mixin;

import net.wdc.core.redirect.HotReads;
import net.wdc.core.redirect.ReadRedirect;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelHotReadMixin {

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void wdc$hotBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (!ReadRedirect.active()) return;
        BlockState state = HotReads.blockState((Level) (Object) this, pos);
        if (state != null) cir.setReturnValue(state);
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void wdc$hotFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (!ReadRedirect.active()) return;
        FluidState state = HotReads.fluidState((Level) (Object) this, pos);
        if (state != null) cir.setReturnValue(state);
    }
}
