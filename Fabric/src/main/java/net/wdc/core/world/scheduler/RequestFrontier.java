package net.wdc.core.world.scheduler;

import net.wdc.core.world.request.RequestAccessPattern;
import net.wdc.core.world.request.RequestScope;
import net.wdc.core.world.storage.ChunkKey;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class RequestFrontier {

    private final RequestScope scope;
    private final RequestAccessPattern accessPattern;
    private final int lookahead;
    private final SchedulerConfig config;

    private int cursorX;
    private int cursorZ;
    private boolean exhausted = false;

    private Iterator<ChunkKey> explicitIterator;

    private final Set<ChunkKey> emitted = new LinkedHashSet<>();

    RequestFrontier(RequestScope scope, RequestAccessPattern accessPattern, int lookahead, SchedulerConfig config) {
        if (scope == null || accessPattern == null || config == null) throw new IllegalArgumentException("args must not be null");
        if (lookahead < 1) throw new IllegalArgumentException("lookahead must be >= 1");
        this.scope = scope;
        this.accessPattern = accessPattern;
        this.lookahead = lookahead;
        this.config = config;
        if (scope.kind() == RequestScope.Kind.RECTANGLE) {
            cursorX = scope.rectangle().minChunkX();
            cursorZ = scope.rectangle().minChunkZ();
        } else {
            explicitIterator = scope.explicitChunks().iterator();
        }
    }

    boolean isExhausted() { return exhausted; }

    List<ChunkKey> nextCandidates(int maxCount) {
        if (exhausted || maxCount < 1) return List.of();
        return switch (accessPattern) {
            case SEQUENTIAL -> sequentialCandidates(Math.min(maxCount, lookahead));
            case NEIGHBORHOOD -> neighborhoodCandidates(maxCount);
            case BATCH -> batchCandidates(Math.min(maxCount, config.defaultBatchWidth));
            case RANDOM -> randomCandidates(maxCount);
            case UNKNOWN -> conservativeCandidates(maxCount);
        };
    }

    void markEmitted(ChunkKey chunk) { emitted.add(chunk); }

    void unmarkEmitted(ChunkKey chunk) {
        emitted.remove(chunk);
        exhausted = false;
    }

    private List<ChunkKey> sequentialCandidates(int count) {
        List<ChunkKey> out = new ArrayList<>(count);
        if (scope.kind() == RequestScope.Kind.EXPLICIT_SET) {
            drainExplicit(out, count);
            return out;
        }
        var rect = scope.rectangle();
        int x = cursorX, z = cursorZ;
        while (out.size() < count && z <= rect.maxChunkZ()) {
            ChunkKey key = new ChunkKey(x, z);
            if (!emitted.contains(key)) out.add(key);
            x++;
            if (x > rect.maxChunkX()) { x = rect.minChunkX(); z++; }
        }
        if (z > rect.maxChunkZ() && out.isEmpty()) exhausted = true;
        return out;
    }

    void advancePast(ChunkKey chunk) {
        if (scope.kind() != RequestScope.Kind.RECTANGLE) return;
        var rect = scope.rectangle();
        if (cursorZ > rect.maxChunkZ()) { exhausted = true; return; }
        if (chunk.chunkZ() < cursorZ || (chunk.chunkZ() == cursorZ && chunk.chunkX() < cursorX)) return;
        cursorX = chunk.chunkX() + 1;
        cursorZ = chunk.chunkZ();
        if (cursorX > rect.maxChunkX()) { cursorX = rect.minChunkX(); cursorZ++; }
        if (cursorZ > rect.maxChunkZ()) exhausted = true;
    }

    private List<ChunkKey> neighborhoodCandidates(int maxCount) {
        List<ChunkKey> out = new ArrayList<>();
        if (scope.kind() == RequestScope.Kind.EXPLICIT_SET) {
            drainExplicit(out, Math.min(maxCount, ringSize(config.defaultNeighborhoodRadius)));
            return out;
        }
        var rect = scope.rectangle();
        int radius = Math.min(config.maxNeighborhoodRadius, config.defaultNeighborhoodRadius);
        int centerX = cursorX, centerZ = cursorZ;
        for (int dz = -radius; dz <= radius && out.size() < maxCount; dz++) {
            for (int dx = -radius; dx <= radius && out.size() < maxCount; dx++) {
                int x = centerX + dx, z = centerZ + dz;
                if (!rect.contains(x, z)) continue;
                ChunkKey key = new ChunkKey(x, z);
                if (!emitted.contains(key)) out.add(key);
            }
        }
        if (out.isEmpty()) exhausted = true;
        return out;
    }

    private static int ringSize(int radius) {
        int side = 2 * radius + 1;
        return side * side;
    }

    private List<ChunkKey> batchCandidates(int count) {
        List<ChunkKey> out = new ArrayList<>(count);
        if (scope.kind() == RequestScope.Kind.EXPLICIT_SET) {
            drainExplicit(out, count);
            return out;
        }
        var rect = scope.rectangle();
        int x = cursorX, z = cursorZ;
        while (out.size() < count && z <= rect.maxChunkZ()) {
            ChunkKey key = new ChunkKey(x, z);
            if (!emitted.contains(key)) out.add(key);
            x++;
            if (x > rect.maxChunkX()) { x = rect.minChunkX(); z++; }
        }
        if (out.isEmpty()) exhausted = true;
        return out;
    }

    private List<ChunkKey> randomCandidates(int maxCount) {
        List<ChunkKey> out = new ArrayList<>(1);
        if (scope.kind() == RequestScope.Kind.EXPLICIT_SET) {
            drainExplicit(out, Math.min(maxCount, 1));
            return out;
        }
        var rect = scope.rectangle();
        if (cursorZ > rect.maxChunkZ()) { exhausted = true; return out; }
        ChunkKey key = new ChunkKey(cursorX, cursorZ);
        if (!emitted.contains(key)) out.add(key);
        return out;
    }

    void advanceRandomPast(ChunkKey chunk) {
        if (scope.kind() != RequestScope.Kind.RECTANGLE) return;
        var rect = scope.rectangle();
        if (chunk.chunkX() != cursorX || chunk.chunkZ() != cursorZ) return;
        cursorX++;
        if (cursorX > rect.maxChunkX()) { cursorX = rect.minChunkX(); cursorZ++; }
        if (cursorZ > rect.maxChunkZ()) exhausted = true;
    }

    private List<ChunkKey> conservativeCandidates(int maxCount) {
        int cap = Math.min(maxCount, Math.max(1, config.defaultLookahead / 2));
        return sequentialCandidates(cap);
    }

    private void drainExplicit(List<ChunkKey> out, int count) {
        while (out.size() < count && explicitIterator.hasNext()) {
            ChunkKey key = explicitIterator.next();
            if (!emitted.contains(key)) out.add(key);
        }
        if (!explicitIterator.hasNext() && out.isEmpty()) exhausted = true;
    }
}
