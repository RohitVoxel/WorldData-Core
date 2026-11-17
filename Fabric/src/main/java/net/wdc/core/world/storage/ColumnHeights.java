package net.wdc.core.world.storage;

import net.wdc.core.world.material.WDCMaterial;

import java.util.Arrays;

public final class ColumnHeights implements SizeEstimated {

    public static final int UNKNOWN = Integer.MIN_VALUE;
    public static final int KIND_SURFACE = 0;
    public static final int KIND_FLOOR = 1;
    public static final int KIND_NO_LEAVES = 2;
    public static final int KIND_SOLID = 3;
    public static final int KIND_WATER = 4;
    public static final int KIND_MOTION = 5;
    public static final int KIND_COUNT = 6;
    public static final int COLUMNS = 256;

    private static final short NONE = Short.MIN_VALUE;
    private static final int ALL_KINDS = (1 << KIND_COUNT) - 1;
    private static final byte[] CLASS_BY_MATERIAL = buildClassTable();
    private static final int UNKNOWN_CLASS = (1 << KIND_SURFACE) | (1 << KIND_SOLID) | (1 << KIND_MOTION) | (1 << KIND_NO_LEAVES) | (1 << KIND_FLOOR);
    private static final ThreadLocal<char[]> LAYER = ThreadLocal.withInitial(() -> new char[COLUMNS]);
    private static final ThreadLocal<short[]> SCRATCH = ThreadLocal.withInitial(() -> new short[KIND_COUNT * COLUMNS]);

    private final short[][] arrays = new short[KIND_COUNT][];
    private final int generation;
    private final int epoch;

    private ColumnHeights(GlobalBlockStateRegistry registry) {
        this.generation = registry == null ? 0 : registry.generation();
        this.epoch = registry == null ? 0 : registry.metaEpoch();
    }

    private static byte[] buildClassTable() {
        WDCMaterial[] materials = WDCMaterial.values();
        byte[] table = new byte[materials.length];
        for (int i = 0; i < materials.length; i++) {
            WDCMaterial material = materials[i];
            int cls = 0;
            if (material != WDCMaterial.AIR) {
                cls |= 1 << KIND_SURFACE;
                if (material == WDCMaterial.WATER) cls |= 1 << KIND_WATER;
                else cls |= 1 << KIND_SOLID;
            }
            table[i] = (byte) cls;
        }
        return table;
    }

    public static int classOf(int token, GlobalBlockStateRegistry registry) {
        int state = token & BlockToken.STATE_ID_MASK;
        if (state == 0 || registry == null || BlockToken.isAir(token)) return 0;
        int id = state - 1;
        long[] table = registry.descriptorTable();
        int[] tokens = registry.tokenTable();
        if (id >= table.length || id >= tokens.length || id >= registry.size()) return UNKNOWN_CLASS;
        long descriptor = table[id];
        int ordinal = (int) (descriptor & 0xFFFFL);
        int cls = ordinal < CLASS_BY_MATERIAL.length ? CLASS_BY_MATERIAL[ordinal] : 0;
        if (cls == 0) return 0;
        boolean motion = (tokens[id] & BlockToken.FLAG_MOTION_BLOCKING) != 0;
        if (motion) {
            cls |= 1 << KIND_MOTION;
            if (!StateDescriptor.hasTag(descriptor, StateTags.LEAVES)) cls |= 1 << KIND_NO_LEAVES;
        }
        if (StateDescriptor.hasTag(descriptor, StateTags.BLOCKS_MOTION)) cls |= 1 << KIND_FLOOR;
        return cls;
    }

    public static ColumnHeights build(WDCChunk chunk, GlobalBlockStateRegistry registry) {
        ColumnHeights heights = new ColumnHeights(registry);
        if (chunk == null || registry == null) return heights;

        short[] data = SCRATCH.get();
        Arrays.fill(data, NONE);
        int[] need = new int[COLUMNS];
        Arrays.fill(need, ALL_KINDS);
        int[] needCount = new int[KIND_COUNT];
        Arrays.fill(needCount, COLUMNS);
        int unresolved = ALL_KINDS;
        int pendingColumns = COLUMNS;
        int[] classes = new int[16];
        char[] layer = LAYER.get();
        int minSectionY = chunk.minSectionY();

        for (int slot = chunk.slotCount() - 1; slot >= 0 && pendingColumns > 0; slot--) {
            if (!chunk.isNonEmptySlot(slot)) continue;
            int baseY = (minSectionY + slot) << 4;
            PackedSection section = chunk.packedAt(slot);
            if (section == null) {
                int cls = classOf(chunk.uniformTokenAt(slot), registry) & unresolved;
                if (cls == 0) continue;
                int y = baseY + 15;
                for (int column = 0; column < COLUMNS; column++) {
                    int hit = need[column] & cls;
                    if (hit == 0) continue;
                    for (int k = 0; k < KIND_COUNT; k++) {
                        if ((hit & (1 << k)) != 0) {
                            data[(k << 8) | column] = (short) y;
                            needCount[k]--;
                        }
                    }
                    need[column] &= ~hit;
                    if (need[column] == 0) pendingColumns--;
                }
                unresolved = unresolvedMask(needCount);
                continue;
            }

            int size = section.size;
            if (classes.length < size) classes = new int[size * 2];
            int any = 0;
            classes[0] = 0;
            for (int i = 1; i < size; i++) {
                int cls = section.counts[i] == 0 ? 0 : classOf(section.palette[i], registry);
                classes[i] = cls;
                any |= cls;
            }
            if ((any & unresolved) == 0) continue;

            for (int localY = 15; localY >= 0 && pendingColumns > 0; localY--) {
                if ((any & unresolved) == 0) break;
                section.copyRange(localY << 8, (localY + 1) << 8, layer);
                int y = baseY + localY;
                for (int position = 0; position < COLUMNS; position++) {
                    int cls = classes[layer[position]];
                    if (cls == 0) continue;
                    int column = ((position & 15) << 4) | (position >>> 4);
                    int hit = need[column] & cls;
                    if (hit == 0) continue;
                    for (int k = 0; k < KIND_COUNT; k++) {
                        if ((hit & (1 << k)) != 0) {
                            data[(k << 8) | column] = (short) y;
                            needCount[k]--;
                        }
                    }
                    need[column] &= ~hit;
                    if (need[column] == 0) pendingColumns--;
                }
                unresolved = unresolvedMask(needCount);
            }
        }
        heights.adopt(data);
        return heights;
    }

    private void adopt(short[] data) {
        short[] surface = slice(data, KIND_SURFACE);
        arrays[KIND_SURFACE] = surface;
        for (int kind = 1; kind < KIND_COUNT; kind++) {
            short[] candidate = slice(data, kind);
            if (candidate == null) continue;
            arrays[kind] = kind != KIND_WATER && surface != null && Arrays.equals(candidate, surface) ? surface : candidate;
        }
    }

    private static short[] slice(short[] data, int kind) {
        int from = kind << 8;
        boolean any = false;
        for (int i = from; i < from + COLUMNS; i++) {
            if (data[i] != NONE) {
                any = true;
                break;
            }
        }
        return any ? Arrays.copyOfRange(data, from, from + COLUMNS) : null;
    }

    private static int unresolvedMask(int[] needCount) {
        int mask = 0;
        for (int k = 0; k < KIND_COUNT; k++) {
            if (needCount[k] > 0) mask |= 1 << k;
        }
        return mask;
    }

    public boolean matches(GlobalBlockStateRegistry registry) {
        return registry != null && registry.generation() == generation && registry.metaEpoch() == epoch;
    }

    public int get(int kind, int column) {
        short[] array = arrays[kind];
        if (array == null) return UNKNOWN;
        short value = array[column];
        return value == NONE ? UNKNOWN : value;
    }

    void set(int kind, int column, int y) {
        short value = y == UNKNOWN ? NONE : (short) y;
        short[] array = arrays[kind];
        if (array == null) {
            if (value == NONE) return;
            array = new short[COLUMNS];
            Arrays.fill(array, NONE);
            arrays[kind] = array;
        } else if (array[column] == value) {
            return;
        } else if (kind == KIND_SURFACE) {
            for (int other = 1; other < KIND_COUNT; other++) {
                if (arrays[other] == array) arrays[other] = array.clone();
            }
        } else if (array == arrays[KIND_SURFACE]) {
            array = array.clone();
            arrays[kind] = array;
        }
        array[column] = value;
    }

    @Override
    public long estimatedBytes() {
        long bytes = 48L + 16L + 8L * KIND_COUNT;
        for (int kind = 0; kind < KIND_COUNT; kind++) {
            short[] array = arrays[kind];
            if (array == null) continue;
            boolean shared = false;
            for (int earlier = 0; earlier < kind; earlier++) {
                if (arrays[earlier] == array) shared = true;
            }
            if (!shared) bytes += 16L + 2L * array.length;
        }
        return bytes;
    }
}
