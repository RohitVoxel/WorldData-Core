package net.wdc.core.world.ingestion;

import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

public interface MinecraftSectionView {

    boolean isEmpty();

    BlockStateKeySnapshot blockStateAt(int localX, int localY, int localZ);

    default BlockState rawStateAt(int localX, int localY, int localZ) {
        return null;
    }

    default BlockState uniformRawState() {
        return null;
    }

    default DetachedStates detachStates() {
        return null;
    }

    default boolean unpackTokens(BlockStateInterner interner, SectionUnpacker unpacker, SectionTarget target) {
        return false;
    }

    interface DetachedStates {

        void forEachDistinct(java.util.function.Consumer<BlockState> consumer);

        BlockState stateAt(int packedIndex);
    }

    record BlockStateKeySnapshot(String blockId, Map<String, String> properties, boolean air) {

        private static final String AIR = "minecraft:air";
        private static final String CAVE_AIR = "minecraft:cave_air";
        private static final String VOID_AIR = "minecraft:void_air";

        public BlockStateKeySnapshot(String blockId, Map<String, String> properties) {
            this(blockId, properties, isVanillaAirId(blockId));
        }

        public static boolean isVanillaAirId(String blockId) {
            return AIR.equals(blockId) || CAVE_AIR.equals(blockId) || VOID_AIR.equals(blockId);
        }
    }
}
