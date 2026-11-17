package net.wdc.core.world.identity;

import net.minecraft.core.BlockPos;

public record IdentityRemovedEntry(BlockPos pos, long seed) {
    public IdentityRemovedEntry {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
    }
}
