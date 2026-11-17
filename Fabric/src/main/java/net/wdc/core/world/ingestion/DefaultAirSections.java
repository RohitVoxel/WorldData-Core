package net.wdc.core.world.ingestion;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.function.Predicate;

public final class DefaultAirSections {

    private static final Predicate<BlockState> NOT_DEFAULT_AIR = state -> !state.is(Blocks.AIR);

    private DefaultAirSections() {}

    public static boolean isEntirelyDefaultAir(LevelChunkSection section) {
        return section.hasOnlyAir() && !section.maybeHas(NOT_DEFAULT_AIR);
    }
}
