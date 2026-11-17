package net.wdc.core.world.ingestion;

import net.wdc.core.world.storage.BlockStateKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BlockStateSnapshots {

    private BlockStateSnapshots() {}

    public static BlockStateKey keyOf(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Map<String, String> properties = new LinkedHashMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
            properties.put(entry.getKey().getName(), valueToString(entry.getKey(), entry.getValue()));
        }
        String blockId = id.toString();
        return properties.isEmpty() ? BlockStateKey.of(blockId) : BlockStateKey.of(blockId, properties);
    }

    public static MinecraftSectionView.BlockStateKeySnapshot of(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Map<String, String> properties = new LinkedHashMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
            properties.put(entry.getKey().getName(), valueToString(entry.getKey(), entry.getValue()));
        }
        return new MinecraftSectionView.BlockStateKeySnapshot(id.toString(), properties, state.isAir());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueToString(Property property, Comparable value) {
        return property.getName(value);
    }
}
