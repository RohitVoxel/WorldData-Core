package net.wdc.core.world.ingestion;

import net.wdc.core.world.work.CoreThreads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ClientChunkAdapter implements MinecraftChunkView {

    private final String dimensionId;
    private final LevelChunk chunk;
    private final Level level;

    public ClientChunkAdapter(String dimensionId, LevelChunk chunk, Level level) {
        this.dimensionId = dimensionId;
        this.chunk = chunk;
        this.level = level;
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
        return level.getSectionYFromSectionIndex(index);
    }

    @Override
    public MinecraftSectionView sectionAt(int index) {
        CoreThreads.requireNotWorker("minecraft section read");
        return new SectionView(chunk.getSections()[index]);
    }

    @Override
    public BiomeSample biomeSample() {
        if (level == null) return null;
        BlockPos center = new BlockPos(chunk.getPos().getMiddleBlockX(), level.getSeaLevel(), chunk.getPos().getMiddleBlockZ());
        Holder<Biome> holder = level.getBiome(center);
        Biome biome = holder.value();
        String biomeId = holder.unwrapKey().map(key -> key.location().toString()).orElse(null);
        if (biomeId == null) return null;
        return new BiomeSample(biomeId, biome.getBaseTemperature(), biome.hasPrecipitation());
    }

    private static final class SectionView implements MinecraftSectionView {
        private final LevelChunkSection section;

        private SectionView(LevelChunkSection section) {
            this.section = section;
        }

        @Override
        public BlockState rawStateAt(int localX, int localY, int localZ) {
            return section.getBlockState(localX, localY, localZ);
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
