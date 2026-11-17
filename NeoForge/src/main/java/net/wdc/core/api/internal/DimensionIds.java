package net.wdc.core.api.internal;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;

public final class DimensionIds {
    private static final int LIMIT = 256;
    private static final ConcurrentHashMap<ResourceKey<Level>, String> NAMES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ResourceKey<Level>> KEYS = new ConcurrentHashMap<>();

    private DimensionIds() {}

    public static String nameOf(ResourceKey<Level> key) {
        String name = NAMES.get(key);
        if (name != null) return name;
        name = key.location().toString();
        if (NAMES.size() < LIMIT) NAMES.put(key, name);
        return name;
    }

    public static ResourceKey<Level> keyOf(String dimensionId) {
        ResourceKey<Level> cached = KEYS.get(dimensionId);
        if (cached != null) return cached;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimensionId));
        if (KEYS.size() < LIMIT) KEYS.put(dimensionId, key);
        return key;
    }
}
