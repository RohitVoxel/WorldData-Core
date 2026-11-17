package net.wdc.core.world.demand;

import net.wdc.core.world.material.WDCMaterial;
import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.WDCSection;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

public final class ChunkOccupancy {

    private static final int MATERIAL_COUNT = WDCMaterial.values().length;
    private static final int AIR_ORDINAL = WDCMaterial.AIR.ordinal();
    private static final int FLAT_MIN_SECTION_Y = -32;
    private static final int FLAT_MAX_SECTION_Y = 63;
    private static final int FLAT_SIZE = FLAT_MAX_SECTION_Y - FLAT_MIN_SECTION_Y + 1;
    private static final ThreadLocal<int[]> SCRATCH = ThreadLocal.withInitial(() -> new int[MATERIAL_COUNT]);

    private final int[] flatMask = new int[FLAT_SIZE];
    private final int[][] flatCounts = new int[FLAT_SIZE][];
    private Int2ObjectOpenHashMap<int[]> overflow;

    private ChunkOccupancy() {}

    public static ChunkOccupancy build(WDCChunk chunk, GlobalBlockStateRegistry registry) {
        ChunkOccupancy occupancy = new ChunkOccupancy();
        if (chunk == null || registry == null) return occupancy;

        int[] scratch = SCRATCH.get();
        for (WDCSection section : chunk.sectionArray()) {
            int limit = section.stateEntryLimit();
            if (limit == 0) continue;

            int mask = 0;
            java.util.Arrays.fill(scratch, 0);
            for (int i = 0; i < limit; i++) {
                int count = section.stateEntryCount(i);
                if (count == 0) continue;
                int ordinal = (int) (registry.descriptorOf(section.stateEntryId(i)) & 0xFFFFL);
                if (ordinal == AIR_ORDINAL || ordinal >= MATERIAL_COUNT) continue;
                mask |= 1 << ordinal;
                scratch[ordinal] += count;
            }
            if (mask == 0) continue;
            occupancy.store(section.sectionY(), mask, scratch);
        }
        return occupancy;
    }

    private void store(int sectionY, int mask, int[] counts) {
        int ranked = Integer.bitCount(mask);
        int[] packed = new int[ranked];
        int rank = 0;
        for (int bits = mask; bits != 0; bits &= bits - 1) packed[rank++] = counts[Integer.numberOfTrailingZeros(bits)];
        int slot = sectionY - FLAT_MIN_SECTION_Y;
        if (slot >= 0 && slot < FLAT_SIZE) {
            flatMask[slot] = mask;
            flatCounts[slot] = packed;
            return;
        }
        if (overflow == null) overflow = new Int2ObjectOpenHashMap<>();
        int[] entry = new int[1 + ranked];
        entry[0] = mask;
        System.arraycopy(packed, 0, entry, 1, ranked);
        overflow.put(sectionY, entry);
    }

    public boolean contains(int sectionY, WDCMaterial material) {
        return (presenceMask(sectionY) & (1 << material.ordinal())) != 0;
    }

    public int countOf(int sectionY, WDCMaterial material) {
        int bit = 1 << material.ordinal();
        int slot = sectionY - FLAT_MIN_SECTION_Y;
        if (slot >= 0 && slot < FLAT_SIZE) {
            int mask = flatMask[slot];
            if ((mask & bit) == 0) return 0;
            return flatCounts[slot][Integer.bitCount(mask & (bit - 1))];
        }
        if (overflow == null) return 0;
        int[] entry = overflow.get(sectionY);
        if (entry == null || (entry[0] & bit) == 0) return 0;
        return entry[1 + Integer.bitCount(entry[0] & (bit - 1))];
    }

    public void addCounts(int sectionY, long[] totals) {
        int slot = sectionY - FLAT_MIN_SECTION_Y;
        int mask;
        int[] counts;
        int offset;
        if (slot >= 0 && slot < FLAT_SIZE) {
            mask = flatMask[slot];
            counts = flatCounts[slot];
            offset = 0;
        } else {
            int[] entry = overflow == null ? null : overflow.get(sectionY);
            if (entry == null) return;
            mask = entry[0];
            counts = entry;
            offset = 1;
        }
        int rank = offset;
        for (int bits = mask; bits != 0; bits &= bits - 1) totals[Integer.numberOfTrailingZeros(bits)] += counts[rank++];
    }

    public boolean isEmpty(int sectionY) {
        return presenceMask(sectionY) == 0;
    }

    public int presenceMask(int sectionY) {
        int slot = sectionY - FLAT_MIN_SECTION_Y;
        if (slot >= 0 && slot < FLAT_SIZE) return flatMask[slot];
        if (overflow == null) return 0;
        int[] entry = overflow.get(sectionY);
        return entry == null ? 0 : entry[0];
    }
}
