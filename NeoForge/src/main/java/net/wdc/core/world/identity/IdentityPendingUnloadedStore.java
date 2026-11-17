package net.wdc.core.world.identity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class IdentityPendingUnloadedStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Identity");

    public static final int MAX_ENTRIES = 5_000;
    static final int RENDER_DISTANCE_BUFFER_CHUNKS = 2;

    private final Map<ChunkPos, ChunkEntry> pendingByChunk = new HashMap<>();
    private int totalEntries = 0;

    private record ChunkEntry(List<BlockPos> positions, List<Long> seeds) {}

    public void store(ChunkPos chunk, List<BlockPos> positions, List<Long> seeds) {
        if (positions.isEmpty()) return;

        pendingByChunk.merge(chunk, new ChunkEntry(positions, seeds),
                (existing, incoming) -> {
                    List<BlockPos> mergedPositions = new ArrayList<>(existing.positions());
                    List<Long> mergedSeeds = new ArrayList<>(existing.seeds());
                    mergedPositions.addAll(incoming.positions());
                    mergedSeeds.addAll(incoming.seeds());
                    return new ChunkEntry(mergedPositions, mergedSeeds);
                });
        totalEntries += positions.size();

        if (totalEntries > MAX_ENTRIES) {
            LOGGER.debug("Identity pending-unload store over cap ({} entries) — caller should prune by distance.", totalEntries);
        }
    }

    public int pruneFar(ChunkPos playerChunk, int renderDistanceChunks) {
        int keepRadius = renderDistanceChunks + RENDER_DISTANCE_BUFFER_CHUNKS;
        int dropped = 0;

        var it = pendingByChunk.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            ChunkPos chunk = entry.getKey();
            int distance = Math.max(Math.abs(chunk.x - playerChunk.x), Math.abs(chunk.z - playerChunk.z));
            if (distance > keepRadius) {
                dropped += entry.getValue().positions().size();
                it.remove();
            }
        }
        totalEntries -= dropped;
        return dropped;
    }

    public int pruneToCapByDistance(ChunkPos playerChunk) {
        if (totalEntries <= MAX_ENTRIES) return 0;

        List<Map.Entry<ChunkPos, ChunkEntry>> sorted = new ArrayList<>(pendingByChunk.entrySet());
        sorted.sort((a, b) -> Integer.compare(chebyshev(b.getKey(), playerChunk), chebyshev(a.getKey(), playerChunk)));

        int dropped = 0;
        int i = 0;
        while (totalEntries > MAX_ENTRIES && i < sorted.size()) {
            var entry = sorted.get(i++);
            int size = entry.getValue().positions().size();
            pendingByChunk.remove(entry.getKey());
            totalEntries -= size;
            dropped += size;
        }
        return dropped;
    }

    private static int chebyshev(ChunkPos a, ChunkPos b) {
        return Math.max(Math.abs(a.x - b.x), Math.abs(a.z - b.z));
    }

    public RestoredChunk take(ChunkPos chunk) {
        ChunkEntry entry = pendingByChunk.remove(chunk);
        if (entry == null) return RestoredChunk.EMPTY;
        totalEntries -= entry.positions().size();
        return new RestoredChunk(entry.positions(), entry.seeds());
    }

    public List<ChunkPos> pendingChunks() {
        return new ArrayList<>(pendingByChunk.keySet());
    }

    public record RestoredChunk(List<BlockPos> positions, List<Long> seeds) {
        static final RestoredChunk EMPTY = new RestoredChunk(List.of(), List.of());

        public boolean isEmpty() {
            return positions.isEmpty();
        }
    }

    public int pendingChunkCount() {
        return pendingByChunk.size();
    }

    public int pendingPositionCount() {
        return totalEntries;
    }

    public void reset() {
        pendingByChunk.clear();
        totalEntries = 0;
    }
}
