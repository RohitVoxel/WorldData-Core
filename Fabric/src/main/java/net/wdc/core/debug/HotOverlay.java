package net.wdc.core.debug;

import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.storage.WDCChunk;

import java.util.ArrayList;
import java.util.List;

public final class HotOverlay {

    public static final int DEFAULT_SECTION_RADIUS = 3;

    public record Box(int chunkX, int sectionY, int chunkZ, boolean hot, boolean pinned) {}

    private static volatile boolean enabled;

    private HotOverlay() {}

    public static boolean enabled() {
        return enabled;
    }

    public static void set(boolean on) {
        enabled = on;
    }

    public static boolean toggle() {
        boolean next = !enabled;
        enabled = next;
        return next;
    }

    public static List<Box> collect(WDCCache cache, String dimensionId, int centerChunkX, int centerChunkZ,
                                    int centerSectionY, int chunkRadius, int sectionRadius) {
        List<Box> boxes = new ArrayList<>();
        if (cache == null || dimensionId == null || chunkRadius < 0 || sectionRadius < 0) return boxes;
        WorldKey world = cache.currentWorldKey(dimensionId);
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                WDCCache.EntryView view = cache.inspect(world, chunkX, chunkZ);
                if (view == null) continue;
                WDCChunk chunk = view.chunk();
                for (int sectionY = centerSectionY - sectionRadius; sectionY <= centerSectionY + sectionRadius; sectionY++) {
                    if (!chunk.hasSection(sectionY)) continue;
                    boxes.add(new Box(chunkX, sectionY, chunkZ, chunk.isHot(sectionY), chunk.isPinned(sectionY)));
                }
            }
        }
        return boxes;
    }
}
