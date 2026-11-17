package net.wdc.core.world.identity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BlockIdentityStore {

    private static final int CHANGE_BUFFER_LIMIT = 10_000;
    private static final int RETAIN_ON_OVERFLOW = 500;

    private final Map<BlockPos, Long> seedByPosition = new HashMap<>();
    private final Map<ChunkPos, Set<BlockPos>> positionsByChunk = new HashMap<>();
    private final Deque<BlockPos> recentChanges = new ArrayDeque<>();
    private boolean changeStreamOverflowed = false;

    public boolean has(BlockPos pos) {
        return seedByPosition.containsKey(pos);
    }

    public long seedFor(BlockPos pos) {
        Long seed = seedByPosition.get(pos);
        return (seed != null) ? seed : IdentitySeedFactory.fallbackSeed(pos);
    }

    public long assignIfAbsent(BlockPos pos) {
        Long existing = seedByPosition.get(pos);
        if (existing != null) return existing;

        long seed = IdentitySeedFactory.newSeed();
        set(pos, seed);
        return seed;
    }

    public void set(BlockPos pos, long seed) {
        Long existing = seedByPosition.get(pos);
        if (existing != null && existing == seed) return;

        BlockPos key = pos.immutable();
        seedByPosition.put(key, seed);
        if (existing == null) indexAdd(key);

        recentChanges.addLast(key);
        trimIfOverflowing();
    }

    public void remove(BlockPos pos) {
        Long existing = seedByPosition.remove(pos);
        if (existing == null) return;

        indexRemove(pos);
        recentChanges.addLast(pos.immutable());
        trimIfOverflowing();
    }

    public List<IdentityRemovedEntry> removeChunk(ChunkPos chunk) {
        Set<BlockPos> positions = positionsByChunk.remove(chunk);
        if (positions == null || positions.isEmpty()) return List.of();

        List<IdentityRemovedEntry> removed = new java.util.ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            Long seed = seedByPosition.remove(pos);
            if (seed == null) continue;
            removed.add(new IdentityRemovedEntry(pos, seed));
        }
        return removed;
    }

    public List<IdentityPositionSnapshot> exportAll() {
        List<IdentityPositionSnapshot> out = new java.util.ArrayList<>(seedByPosition.size());
        for (var entry : seedByPosition.entrySet()) {
            out.add(new IdentityPositionSnapshot(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    public Set<BlockPos> trackedPositions() {
        return Collections.unmodifiableSet(seedByPosition.keySet());
    }

    public int trackedPositionCount() {
        return seedByPosition.size();
    }

    public int trackedChunkCount() {
        return positionsByChunk.size();
    }

    public List<BlockPos> drainRecentChanges() {
        if (recentChanges.isEmpty()) return List.of();
        List<BlockPos> drained = new java.util.ArrayList<>(recentChanges);
        recentChanges.clear();
        return drained;
    }

    public boolean consumeChangeStreamOverflow() {
        if (!changeStreamOverflowed) return false;
        changeStreamOverflowed = false;
        return true;
    }

    private void trimIfOverflowing() {
        if (recentChanges.size() <= CHANGE_BUFFER_LIMIT) return;

        int toDiscard = recentChanges.size() - RETAIN_ON_OVERFLOW;
        for (int i = 0; i < toDiscard; i++) {
            recentChanges.pollFirst();
        }
        changeStreamOverflowed = true;
    }

    private void indexAdd(BlockPos pos) {
        positionsByChunk.computeIfAbsent(new ChunkPos(pos), c -> new HashSet<>()).add(pos);
    }

    private void indexRemove(BlockPos pos) {
        ChunkPos chunk = new ChunkPos(pos);
        Set<BlockPos> positions = positionsByChunk.get(chunk);
        if (positions == null) return;

        positions.remove(pos);
        if (positions.isEmpty()) positionsByChunk.remove(chunk);
    }

    public void reset() {
        seedByPosition.clear();
        positionsByChunk.clear();
        recentChanges.clear();
    }
}
