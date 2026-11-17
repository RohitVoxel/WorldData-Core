package net.wdc.core.world.storage;

import java.util.Arrays;

public final class SectionBuilder {

    public static final int BLOCKS = 4096;
    private static final int LINEAR_LIMIT = 24;
    private static final int MIN_PALETTE = 32;

    private final char[] indices = new char[BLOCKS];
    private int[] palette = new int[MIN_PALETTE];
    private int[] counts = new int[MIN_PALETTE];
    private long[] packed = new long[PackedSection.wordsFor(1)];
    private int[] hashKeys;
    private int[] hashValues;
    private int[] hashStamps;
    private int hashEpoch;
    private boolean hashLive;
    private int[] remap = new int[256];
    private int size = 1;
    private int packedBits;

    public SectionBuilder() {
        palette[0] = BlockToken.AIR;
    }

    public void reset() {
        Arrays.fill(counts, 0, Math.min(size + 1, counts.length), 0);
        size = 1;
        palette[0] = BlockToken.AIR;
        packedBits = 0;
        hashLive = false;
    }

    public char[] indices() {
        return indices;
    }

    public int size() {
        return size;
    }

    int[] palette() {
        return palette;
    }

    int[] counts() {
        return counts;
    }

    long[] packedWords() {
        return packed;
    }

    int packedBits() {
        return packedBits;
    }

    public int[] remapFor(int entries) {
        if (remap.length < entries) remap = new int[Math.max(entries, remap.length * 2)];
        Arrays.fill(remap, 0, entries, -1);
        return remap;
    }

    public int indexOf(int token) {
        int state = token & BlockToken.STATE_ID_MASK;
        if (state == 0) return 0;
        int n = size;
        if (n <= LINEAR_LIMIT) {
            int[] pal = palette;
            for (int i = 1; i < n; i++) {
                if ((pal[i] & BlockToken.STATE_ID_MASK) == state) return i;
            }
            int index = append(token);
            if (size > LINEAR_LIMIT) buildHash();
            return index;
        }
        if (!hashLive) buildHash();
        int mask = hashKeys.length - 1;
        int slot = (state * 0x9E3779B1) >>> 8;
        while (true) {
            slot &= mask;
            if (hashStamps[slot] != hashEpoch) {
                int index = append(token);
                hashStamps[slot] = hashEpoch;
                hashKeys[slot] = state;
                hashValues[slot] = index;
                return index;
            }
            if (hashKeys[slot] == state) return hashValues[slot];
            slot++;
        }
    }

    private int append(int token) {
        int index = size++;
        if (index >= palette.length) {
            int grown = Math.min(BLOCKS + 2, palette.length * 2);
            palette = Arrays.copyOf(palette, grown);
            counts = Arrays.copyOf(counts, grown);
        }
        palette[index] = token;
        return index;
    }

    private void buildHash() {
        if (hashKeys == null) {
            hashKeys = new int[8192];
            hashValues = new int[8192];
            hashStamps = new int[8192];
        }
        hashEpoch++;
        if (hashEpoch == Integer.MAX_VALUE) {
            Arrays.fill(hashStamps, 0);
            hashEpoch = 1;
        }
        hashLive = true;
        int mask = hashKeys.length - 1;
        for (int i = 1; i < size; i++) {
            int state = palette[i] & BlockToken.STATE_ID_MASK;
            int slot = (state * 0x9E3779B1) >>> 8;
            while (true) {
                slot &= mask;
                if (hashStamps[slot] != hashEpoch) {
                    hashStamps[slot] = hashEpoch;
                    hashKeys[slot] = state;
                    hashValues[slot] = i;
                    break;
                }
                slot++;
            }
        }
    }

    public int pack() {
        int bits = PackedSection.bitsFor(size);
        int words = PackedSection.wordsFor(bits);
        if (packed.length < words) packed = new long[words];
        if (counts.length < size + 1) counts = Arrays.copyOf(counts, size + 1);
        Arrays.fill(counts, 0, size + 1, 0);
        PackedSection.pack(indices, bits, packed, counts);
        packedBits = bits;
        return bits;
    }

    public int tokenAt(int index) {
        return palette[index];
    }
}
