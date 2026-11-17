package net.wdc.core.mixin.token;

import net.wdc.core.world.ingestion.WDCTokenHolder;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseTokenMixin implements WDCTokenHolder {

    @Unique
    private volatile long wdc$packed;

    @Override
    public long wdc$packed() {
        return wdc$packed;
    }

    @Override
    public void wdc$setPacked(long packed) {
        this.wdc$packed = packed;
    }
}
