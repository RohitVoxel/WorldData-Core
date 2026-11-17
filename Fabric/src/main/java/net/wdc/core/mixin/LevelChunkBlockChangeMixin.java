package net.wdc.core.mixin;

import net.wdc.core.world.ingestion.IngestionBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkBlockChangeMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void wdc$afterSetBlockState(BlockPos pos, BlockState state, boolean isMoving,
                                          CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() == null) return;
        Level level = ((LevelChunk) (Object) this).getLevel();
        IngestionBridge.notifyBlockChanged(level.isClientSide(), level.dimension(),
                pos.getX(), pos.getY(), pos.getZ(), state);
    }
}
