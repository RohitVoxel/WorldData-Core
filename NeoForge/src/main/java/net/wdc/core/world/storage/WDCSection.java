package net.wdc.core.world.storage;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;

public final class WDCSection {
    private static final double DENSE_OCCUPANCY_THRESHOLD = 0.75;
    private static final ThreadLocal<int[]> TOKEN_SCRATCH = ThreadLocal.withInitial(() -> new int[PackedSection.BLOCKS]);
    private static final int POSITIONS_PER_SECTION = PackedSection.BLOCKS;
    private static final char[] NO_POSITIONS = new char[0];
    private static final ThreadLocal<char[]> POSITION_SCRATCH = ThreadLocal.withInitial(() -> new char[POSITIONS_PER_SECTION]);
    private static final ThreadLocal<boolean[]> BLANK_SCRATCH = ThreadLocal.withInitial(() -> new boolean[PackedSection.BLOCKS + 2]);
    private static final ThreadLocal<boolean[]> MATCH_SCRATCH = ThreadLocal.withInitial(() -> new boolean[POSITIONS_PER_SECTION + 2]);

    private final WDCChunk owner;
    private final int sectionY;

    public WDCSection(int sectionY) {
        this.sectionY = sectionY;
        this.owner = new WDCChunk(0, 0, sectionY, 1);
        this.owner.adopt(this);
    }

    WDCSection(WDCChunk owner, int sectionY) {
        this.owner = owner;
        this.sectionY = sectionY;
    }

    public int sectionY() { return sectionY; }

    WDCChunk owner() { return owner; }

    private int slot() { return owner.slotOf(sectionY); }

    public void inheritVersion(long previousVersion) {
        int slot = slot();
        if (slot < 0) return;
        owner.setSectionVersion(slot, Math.max(owner.sectionVersionAt(slot), previousVersion + 1L));
    }

    public WDCSection detachedCopy() {
        WDCSection copy = new WDCSection(sectionY);
        copy.owner.installSection(this);
        int slot = slot();
        int copySlot = copy.slot();
        if (slot >= 0 && copySlot >= 0) copy.owner.setSectionVersion(copySlot, owner.sectionVersionAt(slot));
        return copy;
    }

    public long version() {
        int slot = slot();
        return slot < 0 ? 0L : owner.sectionVersionAt(slot);
    }

    public int occupiedCount() {
        return owner.occupiedAt(slot());
    }

    public int paletteSize() {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) return 0;
        PackedSection section = owner.packedAt(slot);
        if (section == null) return 1;
        int live = 0;
        for (int i = 1; i < section.size; i++) {
            if (section.counts[i] > 0 && section.isBlockEntry(i)) live++;
        }
        return live;
    }

    public SectionStorageMode mode() {
        int slot = slot();
        int occupied = owner.occupiedAt(slot);
        if (occupied == 0) return SectionStorageMode.EMPTY;
        if (occupied == POSITIONS_PER_SECTION && paletteSize() == 1) return SectionStorageMode.SINGLE_STATE;
        double occupancy = occupied / (double) POSITIONS_PER_SECTION;
        return occupancy >= DENSE_OCCUPANCY_THRESHOLD ? SectionStorageMode.DENSE : SectionStorageMode.SPARSE;
    }

    public int bitsPerEntry() {
        int slot = slot();
        return slot < 0 ? 0 : owner.bitsPerEntryAt(slot);
    }

    public boolean isUniform() {
        int slot = slot();
        return slot >= 0 && owner.packedAt(slot) == null;
    }

    public int uniformToken() {
        return owner.uniformTokenAt(slot());
    }

    public boolean mightContainState(int globalStateId) {
        return owner.mightContainState(slot(), globalStateId);
    }

    public int tokenAtPacked(int packed) {
        int slot = slot();
        if (slot < 0) return BlockToken.AIR;
        return owner.tokenAtSlot(slot, packed);
    }

    public int token(int localX, int localY, int localZ) {
        return tokenAtPacked(PackedPos.pack(localX, localY, localZ));
    }

    public void setBlock(int localX, int localY, int localZ, int globalStateId) {
        setBlockPacked(PackedPos.pack(localX, localY, localZ), globalStateId);
    }

    public void setBlockPacked(int packed, int globalStateId) {
        if (globalStateId == GlobalBlockStateRegistry.NULL_ID) {
            throw new IllegalArgumentException(
                    "globalStateId must not be NULL_ID; use removeBlock() to clear a position");
        }
        setTokenPacked(packed, WDCChunk.tokenForId(globalStateId));
    }

    public boolean setTokenPacked(int packed, int token) {
        owner.getOrCreateSection(sectionY);
        int blockY = (sectionY << 4) + PackedPos.unpackY(packed);
        boolean changed = owner.setToken(PackedPos.unpackX(packed), blockY, PackedPos.unpackZ(packed), token);
        if (!changed) {
            int slot = slot();
            if (slot >= 0) owner.bumpSection(slot);
        }
        return changed;
    }

    public void bulkLoad(int[] positionToGlobalId) {
        if (positionToGlobalId == null || positionToGlobalId.length != POSITIONS_PER_SECTION) {
            throw new IllegalArgumentException("positionToGlobalId must have length " + POSITIONS_PER_SECTION);
        }
        GlobalBlockStateRegistry registry = GlobalBlockStateRegistryProvider.peek();
        int[] tokens = TOKEN_SCRATCH.get();
        int lastId = Integer.MIN_VALUE;
        int lastToken = BlockToken.AIR;
        for (int p = 0; p < POSITIONS_PER_SECTION; p++) {
            int id = positionToGlobalId[p];
            if (id != lastId) {
                lastId = id;
                lastToken = id == GlobalBlockStateRegistry.NULL_ID ? BlockToken.AIR
                        : registry != null && id < registry.size() ? registry.tokenOf(id) : BlockToken.make(id, 0);
            }
            tokens[p] = lastToken;
        }
        long before = version();
        owner.installTokens(sectionY, tokens);
        int slot = slot();
        if (slot >= 0 && owner.sectionVersionAt(slot) == before) owner.bumpSection(slot);
    }

    public boolean contentEquals(int[] positionToGlobalId) {
        if (positionToGlobalId == null || positionToGlobalId.length != POSITIONS_PER_SECTION) {
            throw new IllegalArgumentException("positionToGlobalId must have length " + POSITIONS_PER_SECTION);
        }
        int slot = slot();
        if (slot < 0) {
            for (int p = 0; p < POSITIONS_PER_SECTION; p++) {
                if (positionToGlobalId[p] != GlobalBlockStateRegistry.NULL_ID) return false;
            }
            return true;
        }
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            int expected = BlockToken.globalIdOf(owner.uniformTokenAt(slot));
            for (int p = 0; p < POSITIONS_PER_SECTION; p++) {
                if (positionToGlobalId[p] != expected) return false;
            }
            return true;
        }
        for (int p = 0; p < POSITIONS_PER_SECTION; p++) {
            if (positionToGlobalId[p] != BlockToken.globalIdOf(section.tokenAt(p))) return false;
        }
        return true;
    }

    public boolean reload(int[] positionToGlobalId) {
        if (contentEquals(positionToGlobalId)) return false;
        bulkLoad(positionToGlobalId);
        return true;
    }

    public void copyGlobalIds(int[] out) {
        if (out == null || out.length != POSITIONS_PER_SECTION) {
            throw new IllegalArgumentException("out must have length " + POSITIONS_PER_SECTION);
        }
        int slot = slot();
        PackedSection section = slot < 0 ? null : owner.packedAt(slot);
        if (section == null) {
            Arrays.fill(out, slot < 0 ? GlobalBlockStateRegistry.NULL_ID : BlockToken.globalIdOf(owner.uniformTokenAt(slot)));
            return;
        }
        section.copyTokens(out);
        for (int p = 0; p < POSITIONS_PER_SECTION; p++) out[p] = BlockToken.globalIdOf(out[p]);
    }

    public void copyTokens(int[] out) {
        if (out == null || out.length != POSITIONS_PER_SECTION) {
            throw new IllegalArgumentException("out must have length " + POSITIONS_PER_SECTION);
        }
        int slot = slot();
        PackedSection section = slot < 0 ? null : owner.packedAt(slot);
        if (section == null) {
            Arrays.fill(out, slot < 0 ? BlockToken.AIR : owner.uniformTokenAt(slot));
            return;
        }
        section.copyTokens(out);
    }

    public void copySlots(char[] out) {
        if (out == null || out.length != POSITIONS_PER_SECTION) {
            throw new IllegalArgumentException("out must have length " + POSITIONS_PER_SECTION);
        }
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) {
            Arrays.fill(out, (char) 0);
            return;
        }
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            Arrays.fill(out, (char) 1);
            return;
        }
        section.copyIndices(out);
        blankAirEntries(section, out, 0, POSITIONS_PER_SECTION);
    }

    public void copyLayer(int localY, char[] out) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) {
            Arrays.fill(out, 0, 256, (char) 0);
            return;
        }
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            Arrays.fill(out, 0, 256, (char) 1);
            return;
        }
        section.copyRange(localY << 8, (localY + 1) << 8, out);
        blankAirEntries(section, out, 0, 256);
    }

    public int slotCount(int entry) {
        return stateEntryCount(entry);
    }

    public int globalIdOfSlot(int entry) {
        int limit = stateEntryLimit();
        if (entry < 0 || entry >= limit) throw new IllegalArgumentException("unknown SectionPaletteIndex: " + entry);
        return stateEntryId(entry);
    }

    public long contentHash() {
        int slot = slot();
        return slot < 0 ? 0L : owner.contentHashAt(slot);
    }

    public boolean isContentHashCached() {
        int slot = slot();
        return slot >= 0 && owner.isHashCachedAt(slot);
    }

    public int getBlock(int localX, int localY, int localZ) {
        return getBlockPacked(PackedPos.pack(localX, localY, localZ));
    }

    public int getBlockPacked(int packed) {
        int token = tokenAtPacked(packed);
        return BlockToken.isAir(token) ? GlobalBlockStateRegistry.NULL_ID : BlockToken.globalIdOf(token);
    }

    public int slotAt(int packed) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) return -1;
        PackedSection section = owner.packedAt(slot);
        if (section == null) return BlockToken.isAir(owner.uniformTokenAt(slot)) ? -1 : 0;
        int index = section.indexAt(packed);
        return section.isBlockEntry(index) ? index - 1 : -1;
    }

    public boolean hasBlock(int localX, int localY, int localZ) {
        return !BlockToken.isAir(tokenAtPacked(PackedPos.pack(localX, localY, localZ)));
    }

    public boolean removeBlock(int localX, int localY, int localZ) {
        int packed = PackedPos.pack(localX, localY, localZ);
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) return false;
        if (BlockToken.isAir(tokenAtPacked(packed))) return false;
        return owner.setToken(localX, (sectionY << 4) + localY, localZ, BlockToken.AIR);
    }

    public IntList getPositionsForState(int globalStateId) {
        char[] positions = positionBuffer(globalStateId);
        if (positions.length == 0) return IntLists.EMPTY_LIST;
        IntArrayList out = new IntArrayList(positions.length);
        for (int i = 0; i < positions.length; i++) out.add((int) positions[i]);
        return IntLists.unmodifiable(out);
    }

    public char[] positionBuffer(int globalStateId) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0 || globalStateId < 0) return NO_POSITIONS;
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            if (BlockToken.globalIdOf(owner.uniformTokenAt(slot)) != globalStateId) return NO_POSITIONS;
            char[] all = new char[POSITIONS_PER_SECTION];
            for (int p = 0; p < POSITIONS_PER_SECTION; p++) all[p] = (char) p;
            return all;
        }
        int index = section.find(globalStateId + 1);
        if (index <= 0 || section.counts[index] == 0 || !section.isBlockEntry(index)) return NO_POSITIONS;
        return collectEntry(section, index);
    }

    private static void blankAirEntries(PackedSection section, char[] out, int from, int to) {
        if (!section.hasAirEntries()) return;
        boolean[] blank = BLANK_SCRATCH.get();
        int size = section.size;
        for (int i = 1; i < size; i++) blank[i] = !section.isBlockEntry(i);
        blank[0] = false;
        for (int i = from; i < to; i++) {
            if (blank[out[i]]) out[i] = 0;
        }
    }

    private static void zeroAirEntries(PackedSection section, int[] perSlot) {
        for (int i = 1, n = section.size; i < n; i++) {
            if (!section.isBlockEntry(i)) perSlot[i] = 0;
        }
    }

    private static char[] collectEntry(PackedSection section, int index) {
        boolean[] match = MATCH_SCRATCH.get();
        Arrays.fill(match, 0, section.size, false);
        match[index] = true;
        char[] scratch = POSITION_SCRATCH.get();
        int n = section.collect(match, 0, POSITIONS_PER_SECTION, scratch);
        match[index] = false;
        return Arrays.copyOf(scratch, n);
    }

    public int positionCount(int globalStateId) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0 || globalStateId < 0) return 0;
        PackedSection section = owner.packedAt(slot);
        if (section == null) return BlockToken.globalIdOf(owner.uniformTokenAt(slot)) == globalStateId ? POSITIONS_PER_SECTION : 0;
        int index = section.find(globalStateId + 1);
        return index > 0 && section.isBlockEntry(index) ? section.count(index) : 0;
    }

    public boolean isPacked() { return !isUniform(); }

    public int copyPositionsOfState(int globalStateId, int[] dest) {
        char[] positions = positionBuffer(globalStateId);
        int count = positions.length;
        if (count == 0) return 0;
        if (dest.length < count) throw new IllegalArgumentException("dest too small");
        for (int i = 0; i < count; i++) dest[i] = positions[i];
        return count;
    }

    public void forEachPositionOfState(int globalStateId, IntConsumer consumer) {
        char[] positions = positionBuffer(globalStateId);
        for (int i = 0; i < positions.length; i++) consumer.accept(positions[i]);
    }

    public void forEachOccupiedPosition(StatePositionVisitor visitor) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) return;
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            int id = BlockToken.globalIdOf(owner.uniformTokenAt(slot));
            for (int p = 0; p < POSITIONS_PER_SECTION; p++) visitor.accept(id, p);
            return;
        }
        char[] indices = POSITION_SCRATCH.get();
        section.copyIndices(indices);
        int[] palette = section.palette;
        for (int p = 0; p < POSITIONS_PER_SECTION; p++) {
            int index = indices[p];
            int token = palette[index];
            if ((token & BlockToken.FLAG_NON_AIR) != 0) visitor.accept(BlockToken.globalIdOf(token), p);
        }
    }

    public boolean isDefaultAirOnly() {
        int slot = slot();
        return slot < 0 || owner.isAllDefaultAir(sectionY());
    }

    public int rawEntryLimit() {
        int slot = slot();
        if (slot < 0) return 0;
        PackedSection section = owner.packedAt(slot);
        if (section == null) return BlockToken.isDefaultAir(owner.uniformTokenAt(slot)) ? 0 : 1;
        return section.size - 1;
    }

    public int rawEntryId(int entry) {
        int slot = slot();
        if (slot < 0 || entry < 0) throw new IllegalArgumentException("unknown SectionPaletteIndex: " + entry);
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            if (entry != 0) throw new IllegalArgumentException("unknown SectionPaletteIndex: " + entry);
            return BlockToken.globalIdOf(owner.uniformTokenAt(slot));
        }
        if (entry >= section.size - 1) throw new IllegalArgumentException("unknown SectionPaletteIndex: " + entry);
        return BlockToken.globalIdOf(section.palette[entry + 1]);
    }

    public void copyRawSlots(char[] out) {
        if (out == null || out.length != POSITIONS_PER_SECTION) {
            throw new IllegalArgumentException("out must have length " + POSITIONS_PER_SECTION);
        }
        int slot = slot();
        if (slot < 0) {
            Arrays.fill(out, (char) 0);
            return;
        }
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            Arrays.fill(out, BlockToken.isDefaultAir(owner.uniformTokenAt(slot)) ? (char) 0 : (char) 1);
            return;
        }
        section.copyIndices(out);
    }

    public int stateEntryLimit() {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) return 0;
        PackedSection section = owner.packedAt(slot);
        return section == null ? 1 : section.size - 1;
    }

    public int stateEntryId(int entry) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0 || entry < 0) throw new IllegalArgumentException("unknown SectionPaletteIndex: " + entry);
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            if (entry != 0) throw new IllegalArgumentException("unknown SectionPaletteIndex: " + entry);
            return BlockToken.globalIdOf(owner.uniformTokenAt(slot));
        }
        if (entry >= section.size - 1) throw new IllegalArgumentException("unknown SectionPaletteIndex: " + entry);
        return BlockToken.globalIdOf(section.palette[entry + 1]);
    }

    public int stateEntryCount(int entry) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0 || entry < 0) return 0;
        PackedSection section = owner.packedAt(slot);
        if (section == null) return entry == 0 ? owner.occupiedAt(slot) : 0;
        int index = entry + 1;
        return index < section.size && !section.isBlockEntry(index) ? 0 : section.count(index);
    }

    public char[] stateEntryPositions(int entry) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0 || entry < 0) return NO_POSITIONS;
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            if (entry != 0) return NO_POSITIONS;
            char[] all = new char[POSITIONS_PER_SECTION];
            for (int p = 0; p < POSITIONS_PER_SECTION; p++) all[p] = (char) p;
            return all;
        }
        int index = entry + 1;
        if (index >= section.size || section.counts[index] == 0 || !section.isBlockEntry(index)) return NO_POSITIONS;
        return collectEntry(section, index);
    }

    public interface EntryTest {
        boolean accept(int globalStateId);
    }

    public int countMatching(EntryTest test) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) return 0;
        PackedSection section = owner.packedAt(slot);
        if (section == null) return test.accept(BlockToken.globalIdOf(owner.uniformTokenAt(slot))) ? owner.occupiedAt(slot) : 0;
        int total = 0;
        int[] palette = section.palette;
        char[] counts = section.counts;
        for (int i = 1, n = section.size; i < n; i++) {
            int count = counts[i];
            if (count != 0 && (palette[i] & BlockToken.FLAG_NON_AIR) != 0 && test.accept(BlockToken.globalIdOf(palette[i]))) total += count;
        }
        return total;
    }

    public boolean anyMatching(EntryTest test) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) return false;
        PackedSection section = owner.packedAt(slot);
        if (section == null) return test.accept(BlockToken.globalIdOf(owner.uniformTokenAt(slot)));
        int[] palette = section.palette;
        char[] counts = section.counts;
        for (int i = 1, n = section.size; i < n; i++) {
            if (counts[i] != 0 && (palette[i] & BlockToken.FLAG_NON_AIR) != 0 && test.accept(BlockToken.globalIdOf(palette[i]))) return true;
        }
        return false;
    }

    public int markMatching(EntryTest test, boolean[] matchBySlot, int[] idsBySlot) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) return 0;
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            int id = BlockToken.globalIdOf(owner.uniformTokenAt(slot));
            if (!test.accept(id)) return 0;
            matchBySlot[1] = true;
            idsBySlot[1] = id;
            return owner.occupiedAt(slot);
        }
        int matched = 0;
        int[] palette = section.palette;
        char[] counts = section.counts;
        for (int i = 1, n = section.size; i < n; i++) {
            int count = counts[i];
            if (count == 0 || (palette[i] & BlockToken.FLAG_NON_AIR) == 0) continue;
            int id = BlockToken.globalIdOf(palette[i]);
            if (!test.accept(id)) continue;
            matchBySlot[i] = true;
            idsBySlot[i] = id;
            matched += count;
        }
        return matched;
    }

    public int collectPositionsAndSlots(boolean[] matchBySlot, int fromPosition, int toPosition, char[] positions, char[] slots) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) return 0;
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            if (!matchBySlot[1]) return 0;
            int n = 0;
            for (int p = fromPosition; p < toPosition; p++) {
                positions[n] = (char) p;
                slots[n++] = 1;
            }
            return n;
        }
        return section.collect(matchBySlot, fromPosition, toPosition, positions, slots);
    }

    public int collectPositionsBySlot(boolean[] matchBySlot, char[] out) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) return 0;
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            if (!matchBySlot[1]) return 0;
            for (int p = 0; p < POSITIONS_PER_SECTION; p++) out[p] = (char) p;
            return POSITIONS_PER_SECTION;
        }
        return section.collect(matchBySlot, 0, POSITIONS_PER_SECTION, out);
    }

    public int collectPositionsBySlot(boolean[] matchBySlot, int fromPosition, int toPosition, char[] out) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) return 0;
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            if (!matchBySlot[1]) return 0;
            int n = 0;
            for (int p = fromPosition; p < toPosition; p++) out[n++] = (char) p;
            return n;
        }
        return section.collect(matchBySlot, fromPosition, toPosition, out);
    }

    public void countSlotsInRange(int fromPosition, int toPosition, int[] perSlot) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) return;
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            perSlot[1] += toPosition - fromPosition;
            return;
        }
        section.countRange(fromPosition, toPosition, perSlot);
        zeroAirEntries(section, perSlot);
    }

    public void countSlotsInRangeMasked(int fromPosition, int toPosition, int[] perSlot, byte[] columnTable) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) return;
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            for (int p = fromPosition; p < toPosition; p++) perSlot[1] += columnTable[p & 255];
            return;
        }
        section.countRangeMasked(fromPosition, toPosition, perSlot, columnTable);
        zeroAirEntries(section, perSlot);
    }

    public void copySlotRange(int fromPosition, int toPosition, char[] out) {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) {
            Arrays.fill(out, 0, toPosition - fromPosition, (char) 0);
            return;
        }
        PackedSection section = owner.packedAt(slot);
        if (section == null) {
            Arrays.fill(out, 0, toPosition - fromPosition, (char) 1);
            return;
        }
        section.copyRange(fromPosition, toPosition, out);
        blankAirEntries(section, out, 0, toPosition - fromPosition);
    }

    public List<StateCount> stateCounts() {
        int slot = slot();
        if (slot < 0 || owner.occupiedAt(slot) == 0) return Collections.emptyList();
        PackedSection section = owner.packedAt(slot);
        if (section == null) return List.of(new StateCount(BlockToken.globalIdOf(owner.uniformTokenAt(slot)), POSITIONS_PER_SECTION));
        List<StateCount> out = new ArrayList<>(section.size);
        for (int i = 1; i < section.size; i++) {
            if (section.counts[i] == 0 || !section.isBlockEntry(i)) continue;
            out.add(new StateCount(BlockToken.globalIdOf(section.palette[i]), section.counts[i]));
        }
        return out;
    }

    public long estimatedBytes() {
        int slot = slot();
        if (slot < 0) return 64L;
        return 64L + owner.packedBytesAt(slot);
    }

    public record StateCount(int globalStateId, int count) {}
}
