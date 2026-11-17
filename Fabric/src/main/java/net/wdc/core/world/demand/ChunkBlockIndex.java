package net.wdc.core.world.demand;

import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.WDCSection;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;

public final class ChunkBlockIndex implements net.wdc.core.world.storage.SizeEstimated {

    public enum ColumnKind { EMPTY, ONE_STATE_ONE_Y, ONE_STATE_RANGE, MULTI }

    private static final int COLUMN_COUNT = 256;
    private static final int[] NO_INTS = new int[0];
    private static final char[] NO_LENGTHS = new char[0];
    private static final ThreadLocal<Runs> RUNS = ThreadLocal.withInitial(Runs::new);
    private static final int NO_RUN = Integer.MIN_VALUE;
    private static final int NO_STATE = Integer.MIN_VALUE + 1;
    private static final ThreadLocal<int[]> OPEN_START = ThreadLocal.withInitial(() -> new int[COLUMN_COUNT]);
    private static final ThreadLocal<int[]> OPEN_STATE = ThreadLocal.withInitial(() -> new int[COLUMN_COUNT]);
    private static final ThreadLocal<char[]> SLOTS = ThreadLocal.withInitial(() -> new char[4096]);

    private final int[] columnStart = new int[COLUMN_COUNT + 1];
    private int[] runY = NO_INTS;
    private char[] runLength = NO_LENGTHS;
    private int[] runState = NO_INTS;

    private ChunkBlockIndex() {}

    public static ChunkBlockIndex build(WDCChunk chunk) {
        ChunkBlockIndex index = new ChunkBlockIndex();
        if (chunk == null) return index;

        int minSection = Integer.MAX_VALUE;
        int maxSection = Integer.MIN_VALUE;
        for (WDCSection section : chunk.sections()) {
            minSection = Math.min(minSection, section.sectionY());
            maxSection = Math.max(maxSection, section.sectionY());
        }
        if (minSection > maxSection) return index;

        WDCSection[] ordered = new WDCSection[maxSection - minSection + 1];
        for (WDCSection section : chunk.sections()) ordered[section.sectionY() - minSection] = section;

        Runs runs = RUNS.get();
        runs.reset();
        int[] openStart = OPEN_START.get();
        int[] openState = OPEN_STATE.get();
        java.util.Arrays.fill(openStart, NO_RUN);
        char[] slots = SLOTS.get();
        int[] globalBySlot = new int[32];
        int endY = Integer.MIN_VALUE;

        for (int s = 0; s < ordered.length; s++) {
            WDCSection section = ordered[s];
            int baseY = (minSection + s) << 4;
            if (section == null || section.occupiedCount() == 0) {
                for (int column = 0; column < COLUMN_COUNT; column++) {
                    if (openStart[column] != NO_RUN) runs.add(column, openStart[column], baseY - openStart[column], openState[column]);
                    openStart[column] = NO_RUN;
                }
                continue;
            }
            endY = baseY + 16;
            if (section.isUniform()) {
                int state = section.stateEntryId(0);
                for (int column = 0; column < COLUMN_COUNT; column++) {
                    if (openStart[column] != NO_RUN) {
                        if (openState[column] == state) continue;
                        runs.add(column, openStart[column], baseY - openStart[column], openState[column]);
                    }
                    openStart[column] = baseY;
                    openState[column] = state;
                }
                continue;
            }
            int paletteSize = section.stateEntryLimit();
            if (globalBySlot.length < paletteSize + 1) globalBySlot = new int[(paletteSize + 1) * 2];
            globalBySlot[0] = GlobalBlockStateRegistry.NULL_ID;
            for (int slot = 0; slot < paletteSize; slot++) globalBySlot[slot + 1] = section.stateEntryId(slot);
            section.copySlots(slots);
            for (int xz = 0; xz < COLUMN_COUNT; xz++) {
                transition(runs, openStart, openState, ((xz & 15) << 4) | (xz >> 4), baseY, slots[xz] == 0 ? NO_STATE : globalBySlot[slots[xz]]);
            }
            for (int localY = 1; localY < 16; localY++) {
                int layer = localY << 8;
                int previous = layer - 256;
                int y = baseY + localY;
                int from = 0;
                while (from < COLUMN_COUNT) {
                    int mismatch = java.util.Arrays.mismatch(slots, previous + from, previous + COLUMN_COUNT, slots, layer + from, layer + COLUMN_COUNT);
                    if (mismatch < 0) break;
                    int xz = from + mismatch;
                    int value = slots[layer + xz];
                    transition(runs, openStart, openState, ((xz & 15) << 4) | (xz >> 4), y, value == 0 ? NO_STATE : globalBySlot[value]);
                    from = xz + 1;
                }
            }
        }
        for (int column = 0; column < COLUMN_COUNT; column++) {
            if (openStart[column] != NO_RUN) runs.add(column, openStart[column], endY - openStart[column], openState[column]);
        }
        runs.compactInto(index);
        return index;
    }

    private static void transition(Runs runs, int[] openStart, int[] openState, int column, int y, int state) {
        int start = openStart[column];
        if (state == NO_STATE) {
            if (start != NO_RUN) {
                runs.add(column, start, y - start, openState[column]);
                openStart[column] = NO_RUN;
            }
            return;
        }
        if (start != NO_RUN) {
            if (openState[column] == state) return;
            runs.add(column, start, y - start, openState[column]);
        }
        openStart[column] = y;
        openState[column] = state;
    }

    private static final class Runs {
        private int[] y = new int[1024];
        private char[] length = new char[1024];
        private int[] state = new int[1024];
        private int[] next = new int[1024];
        private final int[] head = new int[COLUMN_COUNT];
        private final int[] tail = new int[COLUMN_COUNT];
        private final int[] perColumn = new int[COLUMN_COUNT];
        private int size;

        Runs() {
            reset();
        }

        void reset() {
            size = 0;
            java.util.Arrays.fill(head, -1);
            java.util.Arrays.fill(tail, -1);
            java.util.Arrays.fill(perColumn, 0);
        }

        void add(int column, int runY, int runLength, int runState) {
            if (size == y.length) {
                int grown = size * 2;
                y = java.util.Arrays.copyOf(y, grown);
                length = java.util.Arrays.copyOf(length, grown);
                state = java.util.Arrays.copyOf(state, grown);
                next = java.util.Arrays.copyOf(next, grown);
            }
            int at = size++;
            y[at] = runY;
            length[at] = (char) runLength;
            state[at] = runState;
            next[at] = -1;
            if (tail[column] < 0) head[column] = at;
            else next[tail[column]] = at;
            tail[column] = at;
            perColumn[column]++;
        }

        void compactInto(ChunkBlockIndex index) {
            int total = 0;
            for (int column = 0; column < COLUMN_COUNT; column++) {
                index.columnStart[column] = total;
                total += perColumn[column];
            }
            index.columnStart[COLUMN_COUNT] = total;
            if (total == 0) return;
            index.runY = new int[total];
            index.runLength = new char[total];
            index.runState = new int[total];
            int out = 0;
            for (int column = 0; column < COLUMN_COUNT; column++) {
                for (int at = head[column]; at >= 0; at = next[at]) {
                    index.runY[out] = y[at];
                    index.runLength[out] = length[at];
                    index.runState[out] = state[at];
                    out++;
                }
            }
        }
    }

    public ColumnKind kindAt(int localX, int localZ) {
        validate(localX, localZ);
        int column = (localX << 4) | localZ;
        int runs = columnStart[column + 1] - columnStart[column];
        if (runs == 0) return ColumnKind.EMPTY;
        if (runs == 1) return runLength[columnStart[column]] == 1 ? ColumnKind.ONE_STATE_ONE_Y : ColumnKind.ONE_STATE_RANGE;
        return ColumnKind.MULTI;
    }

    public int stateIdAt(int localX, int localZ, int blockY) {
        validate(localX, localZ);
        int column = (localX << 4) | localZ;
        int lo = columnStart[column];
        int hi = columnStart[column + 1] - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int start = runY[mid];
            if (blockY < start) {
                hi = mid - 1;
            } else if (blockY >= start + runLength[mid]) {
                lo = mid + 1;
            } else {
                return runState[mid];
            }
        }
        return GlobalBlockStateRegistry.NULL_ID;
    }

    public boolean isPresent(int localX, int localZ, int blockY) {
        return stateIdAt(localX, localZ, blockY) != GlobalBlockStateRegistry.NULL_ID;
    }

    public int runCount() {
        return runY.length;
    }

    @Override
    public long estimatedBytes() {
        return 64L + 4L * columnStart.length + 4L * runY.length + 2L * runLength.length + 4L * runState.length;
    }

    private static void validate(int localX, int localZ) {
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) {
            throw new IllegalArgumentException("local coords must be 0-15");
        }
    }
}
