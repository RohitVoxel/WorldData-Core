package net.wdc.core.world.ingestion;

import net.wdc.core.world.storage.SectionBuilder;

import java.util.Arrays;

public final class SectionUnpacker {

    public static final int UNSET = -1;
    private static final int BLOCKS = SectionBuilder.BLOCKS;
    private static final int LAZY_CAPACITY = 8192;

    @FunctionalInterface
    public interface TokenResolver {
        int tokenOf(int paletteId);
    }

    private int[] lazyKeys;
    private int[] lazyValues;
    private int[] lazyStamps;
    private int lazyEpoch;

    public static int expectedWords(int bits) {
        int perLong = 64 / bits;
        return (BLOCKS + perLong - 1) / perLong;
    }

    public static boolean unpackPalette(long[] raw, int bits, int[] lut, int lutSize, SectionTarget target) {
        if (bits < 1 || bits > 32 || raw == null || raw.length < expectedWords(bits)) return false;
        long print = fingerprint(raw, bits, lut, lutSize);
        if (print == target.expected()) {
            target.setUnchanged(print);
            return true;
        }
        target.setFingerprint(print);
        SectionBuilder builder = target.beginMixed();
        char[] out = builder.indices();
        int[] remap = builder.remapFor(lutSize);
        int perLong = 64 / bits;
        long entryMask = (1L << bits) - 1L;
        int lastId = (int) (raw[0] & entryMask);
        int lastLocal = builder.indexOf(lut[lastId]);
        remap[lastId] = lastLocal;
        int first = lastLocal;
        int diff = 0;
        int p = 0;
        for (int w = 0; p < BLOCKS; w++) {
            long word = raw[w];
            int limit = Math.min(perLong, BLOCKS - p);
            for (int e = 0; e < limit; e++) {
                int id = (int) (word & entryMask);
                word >>>= bits;
                int local;
                if (id == lastId) {
                    local = lastLocal;
                } else {
                    local = remap[id];
                    if (local < 0) {
                        local = builder.indexOf(lut[id]);
                        remap[id] = local;
                    }
                    lastId = id;
                    lastLocal = local;
                }
                out[p++] = (char) local;
                diff |= local ^ first;
            }
        }
        return finish(target, builder, diff, first);
    }

    public boolean unpackLazy(long[] raw, int bits, TokenResolver resolver, SectionTarget target) {
        if (bits < 1 || bits > 32 || raw == null || raw.length < expectedWords(bits)) return false;
        long print = fingerprint(raw, bits, null, 0);
        if (print == target.expected()) {
            target.setUnchanged(print);
            return true;
        }
        target.setFingerprint(print);
        nextEpoch();
        SectionBuilder builder = target.beginMixed();
        char[] out = builder.indices();
        int perLong = 64 / bits;
        long entryMask = (1L << bits) - 1L;
        int lastId = (int) (raw[0] & entryMask);
        int lastLocal = lookup(lastId, resolver, builder);
        int first = lastLocal;
        int diff = 0;
        int p = 0;
        for (int w = 0; p < BLOCKS; w++) {
            long word = raw[w];
            int limit = Math.min(perLong, BLOCKS - p);
            for (int e = 0; e < limit; e++) {
                int id = (int) (word & entryMask);
                word >>>= bits;
                int local;
                if (id == lastId) {
                    local = lastLocal;
                } else {
                    local = lookup(id, resolver, builder);
                    lastId = id;
                    lastLocal = local;
                }
                out[p++] = (char) local;
                diff |= local ^ first;
            }
        }
        return finish(target, builder, diff, first);
    }

    static long fingerprint(long[] raw, int bits, int[] lut, int lutSize) {
        int words = expectedWords(bits);
        long a = 0x9E3779B97F4A7C15L ^ bits;
        long b = 0xC2B2AE3D27D4EB4FL ^ lutSize;
        long c = 0x165667B19E3779F9L;
        long d = 0x27D4EB2F165667C5L;
        int i = 0;
        for (; i + 3 < words; i += 4) {
            a = Long.rotateLeft(a ^ (raw[i] * 0xFF51AFD7ED558CCDL), 27) * 0x9FB21C651E98DF25L;
            b = Long.rotateLeft(b ^ (raw[i + 1] * 0xFF51AFD7ED558CCDL), 29) * 0x9FB21C651E98DF25L;
            c = Long.rotateLeft(c ^ (raw[i + 2] * 0xFF51AFD7ED558CCDL), 31) * 0x9FB21C651E98DF25L;
            d = Long.rotateLeft(d ^ (raw[i + 3] * 0xFF51AFD7ED558CCDL), 33) * 0x9FB21C651E98DF25L;
        }
        for (; i < words; i++) a = Long.rotateLeft(a ^ (raw[i] * 0xFF51AFD7ED558CCDL), 27) * 0x9FB21C651E98DF25L;
        int lutLimit = lut == null ? 0 : Math.min(lutSize, lut.length);
        for (int k = 0; k < lutLimit; k++) b = Long.rotateLeft(b ^ ((long) lut[k] * 0xFF51AFD7ED558CCDL), 29) * 0x9FB21C651E98DF25L;
        long h = a ^ Long.rotateLeft(b, 17) ^ Long.rotateLeft(c, 31) ^ Long.rotateLeft(d, 47);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h == 0L ? 1L : h;
    }

    private static boolean finish(SectionTarget target, SectionBuilder builder, int diff, int first) {
        if (diff == 0) {
            target.setUniform(builder.tokenAt(first));
            return true;
        }
        target.setMixed();
        return true;
    }

    private void nextEpoch() {
        if (lazyStamps == null) {
            lazyKeys = new int[LAZY_CAPACITY];
            lazyValues = new int[LAZY_CAPACITY];
            lazyStamps = new int[LAZY_CAPACITY];
        }
        lazyEpoch++;
        if (lazyEpoch == Integer.MAX_VALUE) {
            Arrays.fill(lazyStamps, 0);
            lazyEpoch = 1;
        }
    }

    private int lookup(int id, TokenResolver resolver, SectionBuilder builder) {
        int slot = (id * 0x9E3779B1) >>> 19;
        while (true) {
            slot &= LAZY_CAPACITY - 1;
            if (lazyStamps[slot] != lazyEpoch) {
                int local = builder.indexOf(resolver.tokenOf(id));
                lazyStamps[slot] = lazyEpoch;
                lazyKeys[slot] = id;
                lazyValues[slot] = local;
                return local;
            }
            if (lazyKeys[slot] == id) return lazyValues[slot];
            slot++;
        }
    }
}
