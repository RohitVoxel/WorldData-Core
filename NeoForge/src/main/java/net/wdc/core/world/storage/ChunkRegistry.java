package net.wdc.core.world.storage;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Collection;
import java.util.Collections;

public final class ChunkRegistry {

    private final Long2ObjectOpenHashMap<WDCChunk> chunks = new Long2ObjectOpenHashMap<>();

    public boolean isLoaded(ChunkKey key) {
        return chunks.containsKey(key.pack());
    }

    public WDCChunk get(ChunkKey key) {
        return chunks.get(key.pack());
    }

    public WDCChunk get(int chunkX, int chunkZ) {
        return chunks.get(ChunkKey.pack(chunkX, chunkZ));
    }

    public WDCChunk getAtBlock(int blockX, int blockZ) {
        return chunks.get(ChunkKey.pack(WDCChunk.chunkXOf(blockX), WDCChunk.chunkZOf(blockZ)));
    }

    public WDCChunk getOrCreate(ChunkKey key) {
        long packed = key.pack();
        WDCChunk existing = chunks.get(packed);
        if (existing != null) return existing;
        WDCChunk created = new WDCChunk(key.chunkX(), key.chunkZ());
        chunks.put(packed, created);
        return created;
    }

    public WDCChunk getOrCreate(ChunkKey key, int minSectionY, int sectionCount) {
        long packed = key.pack();
        WDCChunk existing = chunks.get(packed);
        if (existing != null) return existing;
        WDCChunk created = new WDCChunk(key.chunkX(), key.chunkZ(), minSectionY, sectionCount);
        chunks.put(packed, created);
        return created;
    }

    public WDCChunk remove(ChunkKey key) {
        return chunks.remove(key.pack());
    }

    public int chunkCount() {
        return chunks.size();
    }

    public void put(ChunkKey key, WDCChunk chunk) {
        if (key == null || chunk == null) throw new IllegalArgumentException("key/chunk must not be null");
        chunks.put(key.pack(), chunk);
    }

    public Collection<WDCChunk> chunks() {
        return Collections.unmodifiableCollection(chunks.values());
    }

    public void clear() {
        chunks.clear();
    }
}
