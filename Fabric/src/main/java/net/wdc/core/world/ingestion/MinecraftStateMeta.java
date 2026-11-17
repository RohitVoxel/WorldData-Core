package net.wdc.core.world.ingestion;

import net.wdc.core.world.material.WDCMaterial;
import net.wdc.core.world.material.WDCMaterialClassifier;
import net.wdc.core.world.material.WDCMaterialTag;
import net.wdc.core.world.storage.BlockStateKey;
import net.wdc.core.world.storage.BlockToken;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;
import net.wdc.core.world.storage.StateMeta;
import net.wdc.core.world.storage.StateTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;

import java.util.Map;
import java.util.Optional;

public final class MinecraftStateMeta {

    private static final TagKey<Block> ORES = tag("c:ores");
    private static final TagKey<Block> STONES = tag("c:stones");
    private static final TagKey<Block> COBBLESTONES = tag("c:cobblestones");
    private static final TagKey<Block> BASE_STONE_OVERWORLD = tag("minecraft:base_stone_overworld");
    private static final TagKey<Block> BASE_STONE_NETHER = tag("minecraft:base_stone_nether");
    private static final TagKey<Block> NETHERRACKS = tag("c:netherracks");
    private static final TagKey<Block> END_STONES = tag("c:end_stones");
    private static final TagKey<Block> SAND = tag("minecraft:sand");
    private static final TagKey<Block> GRAVELS = tag("c:gravels");
    private static final TagKey<Block> DIRT = tag("minecraft:dirt");
    private static final TagKey<Block> ICE = tag("minecraft:ice");
    private static final TagKey<Block> SNOW = tag("minecraft:snow");
    private static final TagKey<Block> GLASS_BLOCKS = tag("c:glass_blocks");
    private static final TagKey<Block> GLASS_PANES = tag("c:glass_panes");
    private static final TagKey<Block> WOOL = tag("minecraft:wool");
    private static final TagKey<Block> CROPS = tag("minecraft:crops");
    private static final TagKey<Block> FLOWERS = tag("minecraft:flowers");
    private static final TagKey<Block> SAPLINGS = tag("minecraft:saplings");

    private MinecraftStateMeta() {}

    private static TagKey<Block> tag(String id) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.parse(id));
    }

    public static void install(GlobalBlockStateRegistry registry) {
        if (registry == null) return;
        registry.setMetaResolver(key -> {
            BlockState state = stateOf(key);
            return state == null ? null : describe(state, key);
        });
    }

    public static StateMeta describe(BlockState state, BlockStateKey key) {
        int flags = 0;
        FluidState fluidState = state.getFluidState();
        boolean hasFluid = !fluidState.isEmpty();
        if (hasFluid) flags |= BlockToken.FLAG_HAS_FLUID;
        boolean blocksMotion = state.blocksMotion();
        if (blocksMotion || hasFluid) flags |= BlockToken.FLAG_MOTION_BLOCKING;
        int opacity = lightOpacity(state);
        if (opacity >= 15) flags |= BlockToken.FLAG_OPAQUE;
        int emission = Math.max(0, Math.min(15, state.getLightEmission()));
        if (emission > 0) flags |= BlockToken.FLAG_EMISSIVE;
        if (state.hasBlockEntity()) flags |= BlockToken.FLAG_BLOCK_ENTITY;

        int tags = 0;
        boolean ore = state.is(ORES);
        boolean stone = state.is(STONES) || state.is(COBBLESTONES) || state.is(BASE_STONE_OVERWORLD)
                || state.is(BASE_STONE_NETHER) || state.is(NETHERRACKS) || state.is(END_STONES);
        boolean log = state.is(BlockTags.LOGS);
        boolean planks = state.is(BlockTags.PLANKS);
        boolean leaves = state.is(BlockTags.LEAVES) || state.getBlock() instanceof LeavesBlock;
        boolean sand = state.is(SAND) || state.is(GRAVELS);
        boolean dirt = state.is(DIRT);
        boolean ice = state.is(ICE);
        boolean snow = state.is(SNOW);
        boolean glass = state.is(GLASS_BLOCKS) || state.is(GLASS_PANES);
        boolean wool = state.is(WOOL);
        boolean crop = state.is(CROPS);
        boolean plant = state.is(FLOWERS) || state.is(SAPLINGS);
        if (blocksMotion) tags |= StateTags.BLOCKS_MOTION;
        if (ore) tags |= StateTags.ORE;
        if (stone) tags |= StateTags.STONE;
        if (log) tags |= StateTags.LOG;
        if (planks) tags |= StateTags.PLANKS;
        if (leaves) tags |= StateTags.LEAVES;
        if (sand) tags |= StateTags.SAND;
        if (dirt) tags |= StateTags.DIRT;
        if (ice) tags |= StateTags.ICE;
        if (snow) tags |= StateTags.SNOW;
        if (glass) tags |= StateTags.GLASS;
        if (wool) tags |= StateTags.WOOL;
        if (crop) tags |= StateTags.CROP;
        if (plant) tags |= StateTags.PLANT;
        if (log || leaves) flags |= BlockToken.FLAG_CANOPY;

        WDCMaterialTag material = classify(state, key, ore, stone, log || planks, leaves, sand, dirt, ice, snow, glass, wool, crop, plant);
        if (material.isFluid()) tags |= StateTags.FLUID;
        tags |= StateTags.fromMaterial(material.material());
        if (!key.blockId().startsWith("minecraft:")) {
            flags |= BlockToken.FLAG_CUSTOM_MATERIAL;
            tags |= StateTags.MODDED;
        }
        return new StateMeta(flags, material, MinecraftFluidReader.read(state), tags, emission, opacity);
    }

    private static WDCMaterialTag classify(BlockState state, BlockStateKey key, boolean ore, boolean stone, boolean wood,
                                             boolean leaves, boolean sand, boolean dirt, boolean ice, boolean snow,
                                             boolean glass, boolean wool, boolean crop, boolean plant) {
        if (state.isAir()) return WDCMaterialTag.of(WDCMaterial.AIR);
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty() && state.getBlock() instanceof LiquidBlock) {
            if (fluidState.is(FluidTags.WATER)) return WDCMaterialTag.of(WDCMaterial.WATER);
            if (fluidState.is(FluidTags.LAVA)) return WDCMaterialTag.of(WDCMaterial.LAVA);
            return WDCMaterialTag.unclassifiedFluid();
        }
        WDCMaterial named = WDCMaterialClassifier.classify(key.blockId());
        if (named != WDCMaterial.UNKNOWN) return WDCMaterialTag.of(named);
        if (ore || stone) return WDCMaterialTag.of(WDCMaterial.STONE);
        if (wood) return WDCMaterialTag.of(WDCMaterial.WOOD);
        if (leaves) return WDCMaterialTag.of(WDCMaterial.LEAVES);
        if (sand) return WDCMaterialTag.of(WDCMaterial.SAND);
        if (dirt) return WDCMaterialTag.of(WDCMaterial.DIRT);
        if (ice) return WDCMaterialTag.of(WDCMaterial.ICE);
        if (snow) return WDCMaterialTag.of(WDCMaterial.SNOW);
        if (glass) return WDCMaterialTag.of(WDCMaterial.GLASS);
        if (wool) return WDCMaterialTag.of(WDCMaterial.WOOL);
        if (crop) return WDCMaterialTag.of(WDCMaterial.CROP);
        if (plant) return WDCMaterialTag.of(WDCMaterial.PLANT);
        return WDCMaterialTag.of(WDCMaterial.UNKNOWN);
    }

    private static int lightOpacity(BlockState state) {
        try {
            return Math.max(0, Math.min(15, state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
        } catch (RuntimeException | LinkageError unsupported) {
            return state.canOcclude() ? 15 : 0;
        }
    }

    public static BlockState stateOf(BlockStateKey key) {
        ResourceLocation id = ResourceLocation.tryParse(key.blockId());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == null) return null;
        BlockState state = block.defaultBlockState();
        for (Map.Entry<String, String> entry : key.properties().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) return null;
            state = withValue(state, property, entry.getValue());
            if (state == null) return null;
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        return parsed.isPresent() ? state.setValue(property, parsed.get()) : null;
    }

    public static int refreshAll(GlobalBlockStateRegistry registry) {
        if (registry == null) return 0;
        int refreshed = 0;
        int size = registry.size();
        for (int id = 0; id < size; id++) {
            BlockStateKey key = registry.keyOf(id);
            if (key == null) continue;
            BlockState state = stateOf(key);
            if (state == null) continue;
            registry.refreshMeta(id, describe(state, key));
            refreshed++;
        }
        return refreshed;
    }
}
