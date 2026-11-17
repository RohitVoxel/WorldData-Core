package net.wdc.core.world.ingestion;

import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.DataFreshness;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.governor.IngestionGovernor;
import net.wdc.core.world.storage.ChunkKey;

public final class ChunkSweep {

    private int cursor;

    public int sweep(IngestionGovernor governor, WDCCache cache, WorldKey world, LoadedChunkProbe probe, ChunkViewSource views,
                     int centerChunkX, int centerChunkZ, int radius, int maxChecks) {
        if (governor == null || cache == null || world == null || probe == null || views == null) {
            throw new IllegalArgumentException("sweep arguments must not be null");
        }
        if (radius < 0 || maxChecks <= 0) return 0;
        int side = radius * 2 + 1;
        int area = side * side;
        int checks = Math.min(maxChecks, area);
        int start = Math.floorMod(cursor, area);
        int found = 0;
        for (int i = 0; i < checks; i++) {
            int index = (start + i) % area;
            int chunkX = centerChunkX - radius + index % side;
            int chunkZ = centerChunkZ - radius + index / side;
            if (cache.freshnessOf(world, chunkX, chunkZ) == DataFreshness.LIVE) continue;
            ChunkKey key = new ChunkKey(chunkX, chunkZ);
            if (!probe.isLoaded(world, key)) continue;
            if (governor.offerFromMinecraft(world, key, views)) found++;
        }
        cursor = (start + checks) % area;
        return found;
    }
}
