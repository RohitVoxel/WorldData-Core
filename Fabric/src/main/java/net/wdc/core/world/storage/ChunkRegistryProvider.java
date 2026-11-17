package net.wdc.core.world.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkRegistryProvider {

    private static final Map<String, ChunkRegistry> REGISTRIES = new ConcurrentHashMap<>();

    private ChunkRegistryProvider() {}

    public static ChunkRegistry forDimension(String dimensionId) {
        return REGISTRIES.computeIfAbsent(dimensionId, d -> new ChunkRegistry());
    }

    public static void reset(String dimensionId) {
        ChunkRegistry registry = REGISTRIES.remove(dimensionId);
        if (registry != null) registry.clear();
    }

    public static void resetAll() {
        for (ChunkRegistry registry : REGISTRIES.values()) {
            registry.clear();
        }
        REGISTRIES.clear();
    }
}
