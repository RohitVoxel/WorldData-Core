package net.wdc.core.world.identity;

import net.minecraft.core.BlockPos;

public record IdentityPositionSnapshot(BlockPos pos, long seed) {
    public IdentityPositionSnapshot {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
    }
}
