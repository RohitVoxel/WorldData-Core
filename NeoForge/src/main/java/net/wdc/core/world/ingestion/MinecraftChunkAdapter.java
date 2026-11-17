package net.wdc.core.world.ingestion;

import net.wdc.core.world.work.CoreThreads;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class MinecraftChunkAdapter implements MinecraftChunkView {

    private final String dimensionId;
    private final LevelChunk chunk;
    private final LevelHeightAccessor heightAccessor;

    public MinecraftChunkAdapter(String dimensionId, LevelChunk chunk, LevelHeightAccessor heightAccessor) {
        this.dimensionId = dimensionId;
        this.chunk = chunk;
        this.heightAccessor = heightAccessor;
    }

    @Override
    public String dimensionId() {
        return dimensionId;
    }

    @Override
    public int chunkX() {
        return chunk.getPos().x;
    }

    @Override
    public int chunkZ() {
        return chunk.getPos().z;
    }

    @Override
    public int sectionCount() {
        return chunk.getSections().length;
    }

    @Override
    public int sectionYAt(int index) {
        return heightAccessor.getSectionYFromSectionIndex(index);
    }

    @Override
    public MinecraftSectionView sectionAt(int index) {
        CoreThreads.requireNotWorker("minecraft section read");
        return new SectionView(chunk.getSections()[index]);
    }

    private static final class SectionView implements MinecraftSectionView {
        private final LevelChunkSection section;

        private SectionView(LevelChunkSection section) {
            this.section = section;
        }

        @Override
        public BlockState uniformRawState() {
            BlockState[] only = new BlockState[1];
            int[] seen = new int[1];
            section.maybeHas(state -> {
                seen[0]++;
                only[0] = state;
                return false;
            });
            return seen[0] == 1 ? only[0] : null;
        }

        @Override
        public boolean isEmpty() {
            return DefaultAirSections.isEntirelyDefaultAir(section);
        }

        @Override
        public boolean unpackTokens(BlockStateInterner interner, SectionUnpacker unpacker, SectionTarget target) {
            return PalettedAccess.unpack(section, interner, unpacker, target);
        }

        @Override
        public BlockStateKeySnapshot blockStateAt(int localX, int localY, int localZ) {
            return BlockStateSnapshots.of(section.getBlockState(localX, localY, localZ));
        }
    }
}
