package net.wdc.core.world.storage;

import java.util.Arrays;

final class PackedSection {

    static final int BLOCKS = 4096;
    static final int MAX_BITS = 13;
    private static final int STATE_MASK = BlockToken.STATE_ID_MASK;
    private static final int[] PER_LONG = new int[MAX_BITS + 1];
    private static final long[] DIV_MUL = new long[MAX_BITS + 1];
    private static final int[] WORDS = new int[MAX_BITS + 1];
    private static final int RARE_LIMIT = 128;
    private static final int RARE_TOTAL_LIMIT = 256;
    private static final ThreadLocal<int[]> KEYS = ThreadLocal.withInitial(() -> new int[RARE_TOTAL_LIMIT + RARE_LIMIT]);
    private static final ThreadLocal<char[]> SCRATCH = ThreadLocal.withInitial(() -> new char[BLOCKS]);

    static {
        for (int b = 1; b <= MAX_BITS; b++) {
            PER_LONG[b] = 64 / b;
            DIV_MUL[b] = (1L << 32) / PER_LONG[b] + 1L;
            WORDS[b] = (BLOCKS + PER_LONG[b] - 1) / PER_LONG[b];
        }
    }

    final int bits;
    final int perLong;
    final long mul;
    final long mask;
    final long[] words;
    final int[] palette;
    final char[] counts;
    int size;
    int dead;
    private volatile RareIndex rare;
    private int version;
    int lastIndex;

    private PackedSection(int bits, long[] words, int[] palette, char[] counts, int size, int dead) {
        this.bits = bits;
        this.perLong = PER_LONG[bits];
        this.mul = DIV_MUL[bits];
        this.mask = (1L << bits) - 1L;
        this.words = words;
        this.palette = palette;
        this.counts = counts;
        this.size = size;
        this.dead = dead;
    }

    static int bitsFor(int paletteSize) {
        if (paletteSize <= 2) return 1;
        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    static int wordsFor(int bits) {
        return WORDS[bits];
    }

    private static int capacityFor(int size, int bits) {
        return Math.min(1 << bits, (size + 3) & ~3);
    }

    static void pack(char[] indices, int bits, long[] out, int[] countsOut) {
        int perLong = PER_LONG[bits];
        int p = 0;
        int runValue = indices[0];
        int runLength = 0;
        for (int w = 0; p < BLOCKS; w++) {
            int limit = Math.min(perLong, BLOCKS - p);
            long word = 0L;
            int shift = 0;
            for (int e = 0; e < limit; e++) {
                int v = indices[p++];
                if (v != runValue) {
                    countsOut[runValue] += runLength;
                    runValue = v;
                    runLength = 0;
                }
                runLength++;
                word |= (long) v << shift;
                shift += bits;
            }
            out[w] = word;
        }
        countsOut[runValue] += runLength;
    }

    private static void packWords(char[] indices, int bits, long[] out) {
        int perLong = PER_LONG[bits];
        int p = 0;
        for (int w = 0; p < BLOCKS; w++) {
            int limit = Math.min(perLong, BLOCKS - p);
            long word = 0L;
            int shift = 0;
            for (int e = 0; e < limit; e++) {
                word |= (long) indices[p++] << shift;
                shift += bits;
            }
            out[w] = word;
        }
    }

    static PackedSection fromBuilder(SectionBuilder builder) {
        int size = builder.size();
        int bits = builder.packedBits();
        long[] words = Arrays.copyOf(builder.packedWords(), WORDS[bits]);
        int capacity = capacityFor(size, bits);
        int[] palette = Arrays.copyOf(builder.palette(), capacity);
        char[] counts = new char[capacity];
        int[] source = builder.counts();
        int dead = 0;
        for (int i = 0; i < size; i++) {
            counts[i] = (char) source[i];
            if (i != 0 && source[i] == 0) dead++;
        }
        return new PackedSection(bits, words, palette, counts, size, dead);
    }

    static PackedSection ofUniform(int token) {
        boolean air = BlockToken.isDefaultAir(token);
        long[] words = new long[WORDS[1]];
        int[] palette = new int[4];
        char[] counts = new char[4];
        int size;
        if (air) {
            size = 1;
            counts[0] = (char) BLOCKS;
        } else {
            size = 2;
            palette[1] = token;
            counts[1] = (char) BLOCKS;
            Arrays.fill(words, -1L);
        }
        return new PackedSection(1, words, palette, counts, size, 0);
    }

    PackedSection copy() {
        return new PackedSection(bits, words.clone(), palette.clone(), counts.clone(), size, dead);
    }

    int tokenAt(int p) {
        int w = (int) (((long) p * mul) >>> 32);
        return palette[(int) ((words[w] >>> ((p - w * perLong) * bits)) & mask)];
    }

    int indexAt(int p) {
        int w = (int) (((long) p * mul) >>> 32);
        return (int) ((words[w] >>> ((p - w * perLong) * bits)) & mask);
    }

    private void putIndex(int p, int index) {
        int w = (int) (((long) p * mul) >>> 32);
        int shift = (p - w * perLong) * bits;
        words[w] = (words[w] & ~(mask << shift)) | ((long) index << shift);
    }

    int find(int token) {
        int state = token & STATE_MASK;
        if (state == 0) return 0;
        int[] pal = palette;
        for (int i = 1, n = size; i < n; i++) {
            if ((pal[i] & STATE_MASK) == state) return i;
        }
        return -1;
    }

    int count(int index) {
        return index >= 0 && index < size ? counts[index] : 0;
    }

    boolean isBlockEntry(int index) {
        return !BlockToken.isAir(palette[index]);
    }

    int airCount() {
        int total = counts[0];
        for (int i = 1, n = size; i < n; i++) {
            if (BlockToken.isAir(palette[i])) total += counts[i];
        }
        return total;
    }

    boolean hasAirEntries() {
        for (int i = 1, n = size; i < n; i++) {
            if (counts[i] > 0 && BlockToken.isAir(palette[i])) return true;
        }
        return false;
    }

    int liveEntries() {
        return size - dead;
    }

    boolean isUniformOf(int token) {
        int index = find(token);
        return index >= 0 && counts[index] == BLOCKS;
    }

    PackedSection set(int p, int token) {
        int oldIndex = indexAt(p);
        int newIndex = find(token);
        if (newIndex < 0) {
            if (size < palette.length && size < (1 << bits)) {
                newIndex = size;
                palette[newIndex] = token;
                counts[newIndex] = 0;
                size = newIndex + 1;
            } else {
                return rebuilt(p, token, oldIndex);
            }
        } else if (newIndex != 0 && counts[newIndex] == 0) {
            dead--;
        }
        putIndex(p, newIndex);
        version++;
        lastIndex = newIndex;
        counts[oldIndex]--;
        counts[newIndex]++;
        if (oldIndex != 0 && counts[oldIndex] == 0) {
            dead++;
            if (dead >= 16 && dead * 2 >= size) return rebuilt(-1, 0, 0);
        }
        return this;
    }

    private PackedSection rebuilt(int p, int token, int oldIndex) {
        char[] scratch = SCRATCH.get();
        copyIndices(scratch);
        int n = size;
        int[] map = new int[n];
        int[] newPalette = new int[n + 1];
        int[] newCounts = new int[n + 1];
        newPalette[0] = BlockToken.AIR;
        newCounts[0] = counts[0];
        int live = 1;
        boolean identity = true;
        for (int i = 1; i < n; i++) {
            if (counts[i] > 0) {
                map[i] = live;
                newPalette[live] = palette[i];
                newCounts[live] = counts[i];
                if (live != i) identity = false;
                live++;
            } else {
                map[i] = -1;
                identity = false;
            }
        }
        int newSize = live;
        int resultIndex = 0;
        if (!identity) {
            for (int q = 0; q < BLOCKS; q++) scratch[q] = (char) map[scratch[q]];
        }
        if (p >= 0) {
            int state = token & STATE_MASK;
            int newIndex = state == 0 ? 0 : -1;
            for (int i = 1; i < live && newIndex < 0; i++) {
                if ((newPalette[i] & STATE_MASK) == state) newIndex = i;
            }
            if (newIndex < 0) {
                newIndex = newSize++;
                newPalette[newIndex] = token;
                newCounts[newIndex] = 0;
            }
            int mappedOld = oldIndex == 0 ? 0 : map[oldIndex];
            scratch[p] = (char) newIndex;
            resultIndex = newIndex;
            newCounts[mappedOld]--;
            newCounts[newIndex]++;
        }
        int newBits = bitsFor(newSize);
        long[] newWords = new long[WORDS[newBits]];
        packWords(scratch, newBits, newWords);
        int capacity = capacityFor(newSize, newBits);
        int[] finalPalette = Arrays.copyOf(newPalette, capacity);
        char[] finalCounts = new char[capacity];
        int newDead = 0;
        for (int i = 0; i < newSize; i++) {
            finalCounts[i] = (char) newCounts[i];
            if (i != 0 && newCounts[i] == 0) newDead++;
        }
        PackedSection built = new PackedSection(newBits, newWords, finalPalette, finalCounts, newSize, newDead);
        built.lastIndex = resultIndex;
        return built;
    }

    void copyIndices(char[] out) {
        int p = 0;
        int per = perLong;
        long m = mask;
        int b = bits;
        long[] ws = words;
        for (int w = 0; p < BLOCKS; w++) {
            long word = ws[w];
            int limit = Math.min(per, BLOCKS - p);
            for (int e = 0; e < limit; e++) {
                out[p++] = (char) (word & m);
                word >>>= b;
            }
        }
    }

    void copyRange(int from, int to, char[] out) {
        int p = from;
        int w = (int) (((long) p * mul) >>> 32);
        int e = p - w * perLong;
        int o = 0;
        while (p < to) {
            long word = words[w] >>> (e * bits);
            int limit = Math.min(perLong - e, to - p);
            for (int i = 0; i < limit; i++) {
                out[o++] = (char) (word & mask);
                word >>>= bits;
            }
            p += limit;
            w++;
            e = 0;
        }
    }

    void copyTokens(int[] out) {
        int p = 0;
        int per = perLong;
        long m = mask;
        int b = bits;
        long[] ws = words;
        int[] pal = palette;
        for (int w = 0; p < BLOCKS; w++) {
            long word = ws[w];
            int limit = Math.min(per, BLOCKS - p);
            for (int e = 0; e < limit; e++) {
                out[p++] = pal[(int) (word & m)];
                word >>>= b;
            }
        }
    }

    int collect(boolean[] match, int from, int to, char[] out) {
        return collect(match, from, to, out, null);
    }

    int collect(boolean[] match, int from, int to, char[] out, char[] slotsOut) {
        int matched = 0;
        int total = 0;
        boolean sparse = true;
        for (int i = 1; i < size; i++) {
            if (!match[i]) continue;
            int count = counts[i];
            if (count == 0) continue;
            matched++;
            total += count;
            if (count > RARE_LIMIT) sparse = false;
        }
        if (matched == 0) return 0;
        if (sparse && total <= RARE_TOTAL_LIMIT) {
            int n = gather(match, from, to, out, slotsOut);
            if (n >= 0) return n;
        }
        return scan(match, from, to, out, slotsOut, 0);
    }

    private int gather(boolean[] match, int from, int to, char[] out, char[] slotsOut) {
        char[][] lists = rare().lists;
        int n = 0;
        int sources = 0;
        for (int i = 1; i < size; i++) {
            if (!match[i] || counts[i] == 0) continue;
            char[] list = i < lists.length ? lists[i] : null;
            if (list == null) return -1;
            sources++;
            for (int k = 0; k < list.length; k++) {
                int position = list[k];
                if (position < from || position >= to) continue;
                out[n] = (char) position;
                if (slotsOut != null) slotsOut[n] = (char) i;
                n++;
            }
        }
        if (sources > 1 && n > 1) sortPairs(out, slotsOut, n);
        return n;
    }

    private static void sortPairs(char[] positions, char[] slots, int n) {
        if (slots == null) {
            java.util.Arrays.sort(positions, 0, n);
            return;
        }
        int[] keys = KEYS.get();
        for (int i = 0; i < n; i++) keys[i] = (positions[i] << 13) | slots[i];
        java.util.Arrays.sort(keys, 0, n);
        for (int i = 0; i < n; i++) {
            positions[i] = (char) (keys[i] >>> 13);
            slots[i] = (char) (keys[i] & 0x1FFF);
        }
    }

    private int scan(boolean[] match, int from, int to, char[] out, char[] slotsOut, int start) {
        int n = start;
        int p = from;
        int w = (int) (((long) p * mul) >>> 32);
        int e = p - w * perLong;
        while (p < to) {
            long word = words[w] >>> (e * bits);
            int limit = Math.min(perLong - e, to - p);
            if (slotsOut == null) {
                for (int i = 0; i < limit; i++) {
                    if (match[(int) (word & mask)]) out[n++] = (char) p;
                    word >>>= bits;
                    p++;
                }
            } else {
                for (int i = 0; i < limit; i++) {
                    int index = (int) (word & mask);
                    if (match[index]) {
                        out[n] = (char) p;
                        slotsOut[n++] = (char) index;
                    }
                    word >>>= bits;
                    p++;
                }
            }
            w++;
            e = 0;
        }
        return n;
    }

    private RareIndex rare() {
        RareIndex current = rare;
        int stamp = version;
        if (current != null && current.version == stamp) return current;
        char[][] lists = new char[size][];
        int[] fill = new int[size];
        for (int i = 1; i < size; i++) {
            int count = counts[i];
            if (count > 0 && count <= RARE_LIMIT) lists[i] = new char[count];
        }
        char[] indices = SCRATCH.get();
        copyIndices(indices);
        for (int q = 0; q < BLOCKS; q++) {
            int index = indices[q];
            if (index >= lists.length) continue;
            char[] list = lists[index];
            if (list != null && fill[index] < list.length) list[fill[index]++] = (char) q;
        }
        RareIndex built = new RareIndex(stamp, lists);
        rare = built;
        return built;
    }

    private static final class RareIndex {
        final int version;
        final char[][] lists;

        RareIndex(int version, char[][] lists) {
            this.version = version;
            this.lists = lists;
        }
    }

    void countRange(int from, int to, int[] perEntry) {
        int p = from;
        int w = (int) (((long) p * mul) >>> 32);
        int e = p - w * perLong;
        while (p < to) {
            long word = words[w] >>> (e * bits);
            int limit = Math.min(perLong - e, to - p);
            for (int i = 0; i < limit; i++) {
                perEntry[(int) (word & mask)]++;
                word >>>= bits;
            }
            p += limit;
            w++;
            e = 0;
        }
    }

    void countRangeMasked(int from, int to, int[] perEntry, byte[] columnTable) {
        int p = from;
        int w = (int) (((long) p * mul) >>> 32);
        int e = p - w * perLong;
        while (p < to) {
            long word = words[w] >>> (e * bits);
            int limit = Math.min(perLong - e, to - p);
            for (int i = 0; i < limit; i++) {
                int index = (int) (word & mask);
                if (index != 0) perEntry[index] += columnTable[p & 255];
                word >>>= bits;
                p++;
            }
            w++;
            e = 0;
        }
    }

    boolean sameContent(int[] otherPalette, int otherSize, int otherBits, long[] otherWords) {
        if (otherBits == bits && otherSize == size && samePalette(otherPalette, otherSize)) {
            return wordsEqual(otherWords);
        }
        return false;
    }

    private boolean samePalette(int[] other, int otherSize) {
        for (int i = 1; i < otherSize; i++) {
            if (((palette[i] ^ other[i]) & STATE_MASK) != 0) return false;
        }
        return true;
    }

    private boolean wordsEqual(long[] other) {
        long[] mine = words;
        for (int i = 0; i < mine.length; i++) {
            if (mine[i] != other[i]) return false;
        }
        return true;
    }

    boolean sameContent(PackedSection other) {
        if (other == this) return true;
        if (other.bits == bits && other.size == size && samePalette(other.palette, other.size)) {
            return wordsEqual(other.words);
        }
        for (int p = 0; p < BLOCKS; p++) {
            if (((tokenAt(p) ^ other.tokenAt(p)) & STATE_MASK) != 0) return false;
        }
        return true;
    }

    boolean sameContentAsIndices(char[] indices, int[] otherPalette) {
        for (int p = 0; p < BLOCKS; p++) {
            if (((tokenAt(p) ^ otherPalette[indices[p]]) & STATE_MASK) != 0) return false;
        }
        return true;
    }

    boolean sameContentAsTokens(int[] tokens) {
        for (int p = 0; p < BLOCKS; p++) {
            if (((tokenAt(p) ^ tokens[p]) & STATE_MASK) != 0) return false;
        }
        return true;
    }

    long estimatedBytes() {
        return 64L + 16L + 8L * words.length + 16L + 4L * palette.length + 16L + 2L * counts.length;
    }
}
