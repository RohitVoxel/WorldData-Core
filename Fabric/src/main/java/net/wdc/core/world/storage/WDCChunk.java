package net.wdc.core.world.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class WDCChunk {

    public static final int MAX_SLOTS = 4096;
    private static final int DERIVED_SLOT_COUNT = 16;
    private static final int BLOCKS = PackedSection.BLOCKS;
    private static final ThreadLocal<char[]> HASH_SCRATCH = ThreadLocal.withInitial(() -> new char[PackedSection.BLOCKS]);
    private static final ThreadLocal<SectionBuilder> BUILDERS = ThreadLocal.withInitial(SectionBuilder::new);
    private static final java.lang.invoke.VarHandle HOT_SLOT = java.lang.invoke.MethodHandles.arrayElementVarHandle(int[][].class);

    public static final long HOT_SECTION_BYTES = 4L * PackedSection.BLOCKS + 16L;

    private final int chunkX;
    private final int chunkZ;

    private int minSectionY;
    private int slotCount;
    private int[] uniform = new int[0];
    private PackedSection[] packed = new PackedSection[0];
    private long[] fingerprints = new long[0];
    private int[] occupied = new int[0];
    private long[] versions = new long[0];
    private long[] hashes = new long[0];
    private long[] hashValid = new long[0];
    private long[] presentBits = new long[0];
    private long[] nonEmptyBits = new long[0];
    private WDCSection[] views = new WDCSection[0];
    private int[] heat = new int[0];
    private short[] streaks = new short[0];
    private long[] seenVersions = new long[0];
    private int[][] hot;
    private long[] pinnedBits;
    private int hotCount;
    private int pinnedCount;
    private int presentCount;
    private volatile WDCSection[] orderedSections;
    private volatile boolean released;
    private volatile ColumnHeights heights;

    private volatile long version = 0L;
    private long observedNanos;

    private String biomeId;
    private float rawTemperature;
    private boolean hasPrecipitation;
    private boolean biomePresent;

    private record DerivedEntry(long version, long generation, Object value) {}

    private volatile AtomicReferenceArray<DerivedEntry> derivedSlots;
    private volatile ConcurrentHashMap<Integer, DerivedEntry> overflowDerivedSlots;

    public WDCChunk(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public WDCChunk(int chunkX, int chunkZ, int minSectionY, int sectionCount) {
        this(chunkX, chunkZ);
        if (sectionCount < 0 || sectionCount > MAX_SLOTS) throw new IllegalArgumentException("sectionCount out of range: " + sectionCount);
        if (sectionCount > 0) allocate(minSectionY, sectionCount);
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public long version() {
        return version;
    }

    public void bumpVersion() {
        version++;
    }

    public long observedNanos() {
        return observedNanos;
    }

    public void markObserved(long nanos) {
        this.observedNanos = nanos;
    }

    public void setBiomeSample(String biomeId, float rawTemperature, boolean hasPrecipitation) {
        this.biomeId = biomeId;
        this.rawTemperature = rawTemperature;
        this.hasPrecipitation = hasPrecipitation;
        this.biomePresent = biomeId != null;
    }

    public boolean biomePresent() {
        return biomePresent;
    }

    public String biomeId() {
        return biomeId;
    }

    public float rawTemperature() {
        return rawTemperature;
    }

    public boolean hasPrecipitation() {
        return hasPrecipitation;
    }

    public int minSectionY() {
        return minSectionY;
    }

    public int slotCount() {
        return slotCount;
    }

    public int maxSectionY() {
        return minSectionY + slotCount - 1;
    }

    public boolean isReleased() {
        return released;
    }

    private void allocate(int minY, int count) {
        this.minSectionY = minY;
        this.slotCount = count;
        this.uniform = new int[count];
        this.packed = new PackedSection[count];
        this.fingerprints = new long[count];
        this.occupied = new int[count];
        this.versions = new long[count];
        this.hashes = new long[count];
        int words = (count + 63) >>> 6;
        this.hashValid = new long[words];
        this.presentBits = new long[words];
        this.nonEmptyBits = new long[words];
        this.views = new WDCSection[count];
        this.heat = new int[count];
        this.streaks = new short[0];
        this.seenVersions = new long[0];
        this.hot = null;
        this.pinnedBits = null;
        this.hotCount = 0;
        this.pinnedCount = 0;
    }

    private void ensureRange(int sectionY) {
        if (slotCount == 0) {
            allocate(sectionY, 1);
            return;
        }
        int oldMin = minSectionY;
        int oldMax = oldMin + slotCount - 1;
        if (sectionY >= oldMin && sectionY <= oldMax) return;
        int newMin = Math.min(oldMin, sectionY);
        int newMax = Math.max(oldMax, sectionY);
        long span = (long) newMax - (long) newMin + 1L;
        if (span > MAX_SLOTS) throw new IllegalArgumentException("section range too tall: " + span + " slots");
        int newCount = (int) span;
        int shift = oldMin - newMin;
        int[] u = new int[newCount];
        PackedSection[] pk = new PackedSection[newCount];
        long[] fp = new long[newCount];
        int[] oc = new int[newCount];
        long[] ve = new long[newCount];
        long[] ha = new long[newCount];
        WDCSection[] vw = new WDCSection[newCount];
        int[] he = new int[newCount];
        int[][] ho = hot == null ? null : new int[newCount][];
        int words = (newCount + 63) >>> 6;
        long[] pn = pinnedBits == null ? null : new long[words];
        long[] hv = new long[words];
        long[] pb = new long[words];
        long[] ne = new long[words];
        for (int i = 0; i < slotCount; i++) {
            int j = i + shift;
            u[j] = uniform[i];
            pk[j] = packed[i];
            fp[j] = fingerprints[i];
            oc[j] = occupied[i];
            ve[j] = versions[i];
            ha[j] = hashes[i];
            vw[j] = views[i];
            he[j] = heat[i];
            if (ho != null) ho[j] = hot[i];
            if (pn != null && bit(pinnedBits, i)) setBit(pn, j);
            if (bit(hashValid, i)) setBit(hv, j);
            if (bit(presentBits, i)) setBit(pb, j);
            if (bit(nonEmptyBits, i)) setBit(ne, j);
        }
        minSectionY = newMin;
        slotCount = newCount;
        uniform = u;
        packed = pk;
        fingerprints = fp;
        occupied = oc;
        versions = ve;
        hashes = ha;
        hashValid = hv;
        presentBits = pb;
        nonEmptyBits = ne;
        views = vw;
        heat = he;
        hot = ho;
        pinnedBits = pn;
        streaks = new short[0];
        seenVersions = new long[0];
        orderedSections = null;
    }

    private static boolean bit(long[] bits, int index) {
        return (bits[index >>> 6] & (1L << (index & 63))) != 0L;
    }

    private static void setBit(long[] bits, int index) {
        bits[index >>> 6] |= 1L << (index & 63);
    }

    private static void clearBit(long[] bits, int index) {
        bits[index >>> 6] &= ~(1L << (index & 63));
    }

    int slotOf(int sectionY) {
        int slot = sectionY - minSectionY;
        return slot >= 0 && slot < slotCount ? slot : -1;
    }

    void adopt(WDCSection view) {
        int slot = view.sectionY() - minSectionY;
        markPresent(slot);
        views[slot] = view;
    }

    void bumpSection(int slot) {
        touch(slot);
    }

    boolean isSlotPresent(int slot) {
        return slot >= 0 && slot < slotCount && bit(presentBits, slot);
    }

    private void markPresent(int slot) {
        if (!bit(presentBits, slot)) {
            setBit(presentBits, slot);
            presentCount++;
            orderedSections = null;
        }
    }

    private void markAbsent(int slot) {
        if (bit(presentBits, slot)) {
            clearBit(presentBits, slot);
            presentCount--;
            orderedSections = null;
        }
    }

    private void touch(int slot) {
        versions[slot]++;
        fingerprints[slot] = 0L;
        clearBit(hashValid, slot);
    }

    private void refreshNonEmpty(int slot) {
        if (occupied[slot] > 0) setBit(nonEmptyBits, slot);
        else clearBit(nonEmptyBits, slot);
    }

    public boolean hasSection(int sectionY) {
        return isSlotPresent(slotOf(sectionY));
    }

    public WDCSection getSection(int sectionY) {
        int slot = slotOf(sectionY);
        if (!isSlotPresent(slot)) return null;
        return viewAt(slot);
    }

    WDCSection viewAt(int slot) {
        WDCSection view = views[slot];
        if (view == null) {
            view = new WDCSection(this, minSectionY + slot);
            views[slot] = view;
        }
        return view;
    }

    public WDCSection getOrCreateSection(int sectionY) {
        ensureRange(sectionY);
        int slot = sectionY - minSectionY;
        markPresent(slot);
        return viewAt(slot);
    }

    public WDCSection installSection(WDCSection section) {
        if (section == null) throw new IllegalArgumentException("section must not be null");
        int sectionY = section.sectionY();
        boolean existed = hasSection(sectionY);
        WDCSection previous = existed ? getSection(sectionY) : null;
        ensureRange(sectionY);
        int slot = sectionY - minSectionY;
        WDCChunk source = section.owner();
        if (source == this) {
            markPresent(slot);
            return previous;
        }
        int sourceSlot = source.slotOf(sectionY);
        releaseSlot(slot);
        if (sourceSlot >= 0) copySlotFrom(source, sourceSlot, slot);
        markPresent(slot);
        touch(slot);
        return previous;
    }

    private void copySlotFrom(WDCChunk source, int sourceSlot, int slot) {
        PackedSection section = source.packed[sourceSlot];
        if (section == null) {
            uniform[slot] = source.uniform[sourceSlot];
            packed[slot] = null;
        } else {
            packed[slot] = section.copy();
            uniform[slot] = 0;
        }
        occupied[slot] = source.occupied[sourceSlot];
        versions[slot] = Math.max(versions[slot], source.versions[sourceSlot]);
        refreshNonEmpty(slot);
        heights = null;
        resyncHot(slot);
    }

    private void releaseSlot(int slot) {
        dropHot(slot);
        packed[slot] = null;
        fingerprints[slot] = 0L;
        uniform[slot] = 0;
        occupied[slot] = 0;
        clearBit(nonEmptyBits, slot);
        heights = null;
    }

    public boolean removeSection(int sectionY) {
        int slot = slotOf(sectionY);
        if (!isSlotPresent(slot)) return false;
        releaseSlot(slot);
        markAbsent(slot);
        touch(slot);
        return true;
    }

    public void primeContentHashes() {
        for (int slot = 0; slot < slotCount; slot++) {
            if (bit(presentBits, slot)) contentHashAt(slot);
        }
    }

    public WDCChunk detachedCopy() {
        WDCChunk copy = new WDCChunk(chunkX, chunkZ);
        if (slotCount > 0) {
            copy.allocate(minSectionY, slotCount);
            for (int slot = 0; slot < slotCount; slot++) {
                if (!bit(presentBits, slot)) continue;
                copy.copySlotFrom(this, slot, slot);
                copy.versions[slot] = versions[slot];
                if (bit(hashValid, slot)) {
                    copy.hashes[slot] = hashes[slot];
                    setBit(copy.hashValid, slot);
                }
                copy.markPresent(slot);
            }
        }
        copy.version = version;
        copy.observedNanos = observedNanos;
        copy.biomeId = biomeId;
        copy.rawTemperature = rawTemperature;
        copy.hasPrecipitation = hasPrecipitation;
        copy.biomePresent = biomePresent;
        return copy;
    }

    public void releaseToPool() {
        released = true;
        for (int slot = 0; slot < slotCount; slot++) {
            releaseSlot(slot);
            views[slot] = null;
        }
        Arrays.fill(presentBits, 0L);
        Arrays.fill(nonEmptyBits, 0L);
        Arrays.fill(hashValid, 0L);
        Arrays.fill(heat, 0);
        hot = null;
        pinnedBits = null;
        hotCount = 0;
        pinnedCount = 0;
        presentCount = 0;
        orderedSections = null;
    }

    public WDCSection[] sectionArray() {
        WDCSection[] cached = orderedSections;
        if (cached != null) return cached;
        WDCSection[] built = new WDCSection[presentCount];
        int n = 0;
        for (int slot = 0; slot < slotCount && n < built.length; slot++) {
            if (bit(presentBits, slot)) built[n++] = viewAt(slot);
        }
        if (n != built.length) built = Arrays.copyOf(built, n);
        orderedSections = built;
        return built;
    }

    public Collection<WDCSection> sections() {
        WDCSection[] ordered = sectionArray();
        List<WDCSection> out = new ArrayList<>(ordered.length);
        Collections.addAll(out, ordered);
        return Collections.unmodifiableList(out);
    }

    public List<WDCSection> sectionsTopDown() {
        WDCSection[] ordered = sectionArray();
        List<WDCSection> out = new ArrayList<>(ordered.length);
        for (int i = ordered.length - 1; i >= 0; i--) out.add(ordered[i]);
        return out;
    }

    public long estimatedBytes() {
        long bytes = 320L + 4L * uniform.length + 8L * packed.length
                + 4L * occupied.length + 8L * versions.length + 8L * hashes.length + 8L * views.length;
        for (int slot = 0; slot < slotCount; slot++) {
            PackedSection section = packed[slot];
            if (section != null) bytes += section.estimatedBytes();
            if (views[slot] != null) bytes += 24L;
        }
        ColumnHeights published = heights;
        if (published != null) bytes += published.estimatedBytes();
        bytes += hotCount * HOT_SECTION_BYTES;
        return bytes;
    }

    public long derivedEstimatedBytes() {
        AtomicReferenceArray<DerivedEntry> slots = derivedSlots;
        if (slots == null) return 0L;
        long bytes = 0L;
        for (int slot = 0; slot < slots.length(); slot++) {
            DerivedEntry entry = slots.get(slot);
            if (entry != null && entry.value() instanceof SizeEstimated sized) bytes += sized.estimatedBytes();
        }
        return bytes;
    }

    public int sectionCount() {
        return presentCount;
    }

    public int occupiedAt(int slot) {
        return slot >= 0 && slot < slotCount ? occupied[slot] : 0;
    }

    public int uniformTokenAt(int slot) {
        return slot >= 0 && slot < slotCount ? uniform[slot] : BlockToken.AIR;
    }

    PackedSection packedAt(int slot) {
        return slot >= 0 && slot < slotCount ? packed[slot] : null;
    }

    public long fingerprintAt(int sectionY) {
        int slot = sectionY - minSectionY;
        return slot >= 0 && slot < slotCount ? fingerprints[slot] : 0L;
    }

    public void setFingerprintAt(int sectionY, long fingerprint) {
        int slot = sectionY - minSectionY;
        if (slot >= 0 && slot < slotCount) fingerprints[slot] = fingerprint;
    }

    public int bitsPerEntryAt(int slot) {
        PackedSection section = packedAt(slot);
        return section == null ? 0 : section.bits;
    }

    public long packedBytesAt(int slot) {
        PackedSection section = packedAt(slot);
        return section == null ? 0L : section.estimatedBytes();
    }

    public ColumnHeights heights() {
        return heights;
    }

    public ColumnHeights publishHeights(GlobalBlockStateRegistry registry) {
        ColumnHeights current = heights;
        if (current != null && current.matches(registry)) return current;
        if (registry == null) return null;
        ColumnHeights built = ColumnHeights.build(this, registry);
        heights = built;
        return built;
    }

    public void invalidateHeights() {
        heights = null;
    }

    public boolean isNonEmptySlot(int slot) {
        return slot >= 0 && slot < slotCount && bit(nonEmptyBits, slot);
    }

    public int highestNonEmptySlot() {
        for (int word = nonEmptyBits.length - 1; word >= 0; word--) {
            long bits = nonEmptyBits[word];
            if (bits != 0L) return (word << 6) + 63 - Long.numberOfLeadingZeros(bits);
        }
        return -1;
    }

    public int lowestNonEmptySlot() {
        for (int word = 0; word < nonEmptyBits.length; word++) {
            long bits = nonEmptyBits[word];
            if (bits != 0L) return (word << 6) + Long.numberOfTrailingZeros(bits);
        }
        return -1;
    }

    public long sectionVersionAt(int slot) {
        return slot >= 0 && slot < slotCount ? versions[slot] : 0L;
    }

    public boolean mightContainState(int slot, int globalId) {
        if (slot < 0 || slot >= slotCount || occupied[slot] == 0 || globalId < 0) return false;
        PackedSection section = packed[slot];
        if (section == null) return (uniform[slot] & BlockToken.STATE_ID_MASK) == globalId + 1;
        int index = section.find(globalId + 1);
        return index > 0 && section.counts[index] > 0 && section.isBlockEntry(index);
    }

    public boolean containsBlockY(int blockY) {
        int slot = (blockY >> 4) - minSectionY;
        return slot >= 0 && slot < slotCount;
    }

    public boolean isAllDefaultAir(int sectionY) {
        int slot = slotOf(sectionY);
        if (!isSlotPresent(slot)) return true;
        PackedSection section = packed[slot];
        if (section == null) return BlockToken.isDefaultAir(uniform[slot]);
        return section.counts[0] == BLOCKS;
    }

    public int getToken(int blockX, int blockY, int blockZ) {
        int slot = (blockY >> 4) - minSectionY;
        if (slot < 0 || slot >= slotCount) return BlockToken.AIR;
        return tokenAtSlot(slot, ((blockY & 15) << 8) | ((blockZ & 15) << 4) | (blockX & 15));
    }

    int tokenAtSlot(int slot, int position) {
        heat[slot]++;
        int[][] hotSlots = hot;
        if (hotSlots != null) {
            int[] direct = (int[]) HOT_SLOT.getAcquire(hotSlots, slot);
            if (direct != null) return direct[position];
        }
        PackedSection section = packed[slot];
        if (section == null) return uniform[slot];
        return section.tokenAt(position);
    }

    public int getBlock(int blockX, int blockY, int blockZ) {
        return BlockToken.globalIdOf(getToken(blockX, blockY, blockZ));
    }

    public void setBlock(int blockX, int blockY, int blockZ, int globalStateId) {
        if (globalStateId == GlobalBlockStateRegistry.NULL_ID) {
            throw new IllegalArgumentException("globalStateId must not be NULL_ID; use removeBlock() to clear a position");
        }
        if (setToken(blockX, blockY, blockZ, tokenForId(globalStateId))) version++;
    }

    public boolean removeBlock(int blockX, int blockY, int blockZ) {
        int slot = slotOf(sectionYOf(blockY));
        if (!isSlotPresent(slot)) return false;
        if (BlockToken.isDefaultAir(getToken(blockX, blockY, blockZ))) return false;
        boolean removed = setToken(blockX, blockY, blockZ, BlockToken.AIR);
        if (removed) version++;
        return removed;
    }

    static int tokenForId(int globalStateId) {
        GlobalBlockStateRegistry registry = GlobalBlockStateRegistryProvider.peek();
        if (registry != null && globalStateId < registry.size()) return registry.tokenOf(globalStateId);
        return BlockToken.make(globalStateId, 0);
    }

    public boolean setToken(int blockX, int blockY, int blockZ, int token) {
        int sectionY = sectionYOf(blockY);
        boolean defaultAir = BlockToken.isDefaultAir(token);
        int slot = slotOf(sectionY);
        if (defaultAir && (!isSlotPresent(slot))) return false;
        if (!defaultAir) {
            ensureRange(sectionY);
            slot = sectionY - minSectionY;
        }
        markPresent(slot);
        int position = ((blockY & 15) << 8) | ((blockZ & 15) << 4) | (blockX & 15);
        PackedSection section = packed[slot];
        int old;
        if (section == null) {
            old = uniform[slot];
            if (BlockToken.sameState(old, token)) return false;
            section = PackedSection.ofUniform(old);
            packed[slot] = section;
            uniform[slot] = 0;
        } else {
            old = section.tokenAt(position);
            if (BlockToken.sameState(old, token)) return false;
        }
        PackedSection next = section.set(position, token);
        if (next != section) packed[slot] = next;
        boolean newAir = BlockToken.isAir(token);
        boolean oldAir = BlockToken.isAir(old);
        if (newAir && !oldAir) {
            occupied[slot]--;
        } else if (!newAir && oldAir) {
            occupied[slot]++;
        }
        refreshNonEmpty(slot);
        touch(slot);
        if (next.counts[next.lastIndex] == BLOCKS) {
            uniform[slot] = token;
            packed[slot] = null;
        }
        if (hot != null) syncHotAfterSet(slot, position);
        updateHeights(blockX, blockY, blockZ, old, token);
        return true;
    }

    private void updateHeights(int blockX, int blockY, int blockZ, int oldToken, int newToken) {
        ColumnHeights current = heights;
        if (current == null) return;
        GlobalBlockStateRegistry registry = GlobalBlockStateRegistryProvider.peek();
        if (registry == null || !current.matches(registry)) {
            heights = null;
            return;
        }
        int oldClass = ColumnHeights.classOf(oldToken, registry);
        int newClass = ColumnHeights.classOf(newToken, registry);
        if (oldClass == newClass) return;
        int column = ((blockX & 15) << 4) | (blockZ & 15);
        for (int kind = 0; kind < ColumnHeights.KIND_COUNT; kind++) {
            int bit = 1 << kind;
            boolean had = (oldClass & bit) != 0;
            boolean has = (newClass & bit) != 0;
            if (has == had) continue;
            int top = current.get(kind, column);
            if (has) {
                if (top == ColumnHeights.UNKNOWN || blockY > top) current.set(kind, column, blockY);
            } else if (top == blockY) {
                current.set(kind, column, scanDown(kind, blockX, blockZ, blockY - 1, registry));
            }
        }
    }

    private int scanDown(int kind, int blockX, int blockZ, int fromY, GlobalBlockStateRegistry registry) {
        int bit = 1 << kind;
        int y = fromY;
        int floor = minSectionY << 4;
        while (y >= floor) {
            int slot = (y >> 4) - minSectionY;
            if (slot < 0 || slot >= slotCount || occupied[slot] == 0) {
                y = ((y >> 4) << 4) - 1;
                continue;
            }
            PackedSection section = packed[slot];
            if (section == null) {
                if ((ColumnHeights.classOf(uniform[slot], registry) & bit) != 0) return y;
                y = ((y >> 4) << 4) - 1;
                continue;
            }
            int position = ((y & 15) << 8) | ((blockZ & 15) << 4) | (blockX & 15);
            if ((ColumnHeights.classOf(section.tokenAt(position), registry) & bit) != 0) return y;
            y--;
        }
        return ColumnHeights.UNKNOWN;
    }

    public boolean installUniform(int sectionY, int token) {
        ensureRange(sectionY);
        int slot = sectionY - minSectionY;
        boolean existed = bit(presentBits, slot);
        PackedSection section = packed[slot];
        fingerprints[slot] = 0L;
        int newOccupied = BlockToken.isAir(token) ? 0 : BLOCKS;
        if (section == null) {
            if (existed && BlockToken.sameState(uniform[slot], token)) return false;
            dropHot(slot);
            uniform[slot] = token;
            occupied[slot] = newOccupied;
            refreshNonEmpty(slot);
            markPresent(slot);
            touch(slot);
            heights = null;
            return true;
        }
        boolean same = section.isUniformOf(token);
        dropHot(slot);
        packed[slot] = null;
        uniform[slot] = token;
        occupied[slot] = newOccupied;
        refreshNonEmpty(slot);
        markPresent(slot);
        if (!same) {
            touch(slot);
            heights = null;
            return true;
        }
        return false;
    }

    public boolean installTokens(int sectionY, int[] tokens) {
        if (tokens == null || tokens.length != BLOCKS) throw new IllegalArgumentException("tokens must have length " + BLOCKS);
        int first = tokens[0];
        int diff = 0;
        for (int p = 1; p < BLOCKS; p++) diff |= (tokens[p] ^ first) & BlockToken.STATE_ID_MASK;
        if (diff == 0) return installUniform(sectionY, first);
        SectionBuilder builder = BUILDERS.get();
        builder.reset();
        char[] indices = builder.indices();
        int lastToken = first;
        int lastIndex = builder.indexOf(first);
        for (int p = 0; p < BLOCKS; p++) {
            int token = tokens[p];
            if (((token ^ lastToken) & BlockToken.STATE_ID_MASK) != 0) {
                lastToken = token;
                lastIndex = builder.indexOf(token);
            }
            indices[p] = (char) lastIndex;
        }
        return installMixed(sectionY, builder);
    }

    public boolean installMixed(int sectionY, SectionBuilder builder) {
        if (builder == null) throw new IllegalArgumentException("builder must not be null");
        ensureRange(sectionY);
        int slot = sectionY - minSectionY;
        PackedSection existing = packed[slot];
        boolean existed = bit(presentBits, slot);
        builder.pack();
        if (existing != null && existed) {
            if (existing.sameContent(builder.palette(), builder.size(), builder.packedBits(), builder.packedWords())
                    || existing.sameContentAsIndices(builder.indices(), builder.palette())) {
                return false;
            }
        }
        PackedSection built = PackedSection.fromBuilder(builder);
        packed[slot] = built;
        uniform[slot] = 0;
        occupied[slot] = BLOCKS - built.airCount();
        refreshNonEmpty(slot);
        markPresent(slot);
        touch(slot);
        heights = null;
        resyncHot(slot);
        return true;
    }

    private void dropHot(int slot) {
        int[][] hotSlots = hot;
        if (hotSlots == null || hotSlots[slot] == null) return;
        HOT_SLOT.setRelease(hotSlots, slot, (int[]) null);
        hotCount--;
    }

    private void buildHot(int slot, PackedSection section) {
        int[][] hotSlots = hot;
        int[] direct = hotSlots[slot];
        boolean created = direct == null;
        if (created) direct = new int[BLOCKS];
        char[] indices = HASH_SCRATCH.get();
        section.copyIndices(indices);
        int[] palette = section.palette;
        for (int p = 0; p < BLOCKS; p++) direct[p] = palette[indices[p]];
        if (created) {
            HOT_SLOT.setRelease(hotSlots, slot, direct);
            hotCount++;
        }
    }

    private boolean pinnedAt(int slot) {
        return pinnedBits != null && bit(pinnedBits, slot);
    }

    private void resyncHot(int slot) {
        if (hot == null) return;
        PackedSection section = packed[slot];
        if (section != null && pinnedAt(slot)) buildHot(slot, section);
        else dropHot(slot);
    }

    private void syncHotAfterSet(int slot, int position) {
        PackedSection section = packed[slot];
        int[] direct = (int[]) HOT_SLOT.getAcquire(hot, slot);
        if (section == null) {
            if (direct != null) dropHot(slot);
            return;
        }
        if (direct != null) direct[position] = section.tokenAt(position);
        else if (pinnedAt(slot)) buildHot(slot, section);
    }

    private void ensureHotTable() {
        if (hot == null) hot = new int[slotCount][];
    }

    public boolean isHot(int sectionY) {
        int slot = slotOf(sectionY);
        return slot >= 0 && hot != null && hot[slot] != null;
    }

    public boolean isPinned(int sectionY) {
        int slot = slotOf(sectionY);
        return slot >= 0 && pinnedAt(slot);
    }

    public int hotSectionCount() {
        return hotCount;
    }

    public int pinnedSectionCount() {
        return pinnedCount;
    }

    public int autoHotSectionCount() {
        int[][] hotSlots = hot;
        if (hotSlots == null) return 0;
        int auto = 0;
        for (int slot = 0; slot < slotCount; slot++) {
            if (hotSlots[slot] != null && !pinnedAt(slot)) auto++;
        }
        return auto;
    }

    public boolean setPinned(int sectionY, boolean pinned) {
        int slot = slotOf(sectionY);
        if (slot < 0) return false;
        if (pinned) {
            if (pinnedBits == null) pinnedBits = new long[(slotCount + 63) >>> 6];
            ensureHotTable();
            if (!bit(pinnedBits, slot)) {
                setBit(pinnedBits, slot);
                pinnedCount++;
            }
            PackedSection section = packed[slot];
            if (section != null && hot[slot] == null) buildHot(slot, section);
            return true;
        }
        if (pinnedAt(slot)) {
            clearBit(pinnedBits, slot);
            pinnedCount--;
        }
        return true;
    }

    public boolean promoteHot(int sectionY) {
        int slot = slotOf(sectionY);
        if (slot < 0 || !isSlotPresent(slot)) return false;
        PackedSection section = packed[slot];
        if (section == null) return false;
        ensureHotTable();
        if (hot[slot] == null) buildHot(slot, section);
        if (streaks.length > slot) streaks[slot] = 0;
        return true;
    }

    public boolean demoteHot(int sectionY) {
        int slot = slotOf(sectionY);
        if (slot < 0 || pinnedAt(slot) || hot == null || hot[slot] == null) return false;
        dropHot(slot);
        return true;
    }

    public int demoteAllUnpinned() {
        int[][] hotSlots = hot;
        if (hotSlots == null) return 0;
        int dropped = 0;
        for (int slot = 0; slot < slotCount; slot++) {
            if (hotSlots[slot] != null && !pinnedAt(slot)) {
                dropHot(slot);
                dropped++;
            }
        }
        return dropped;
    }

    public void rollHeat(HeatScan.Rules rules, HeatScan.Sink sink) {
        if (slotCount == 0) return;
        if (streaks.length != slotCount) {
            streaks = new short[slotCount];
            seenVersions = versions.clone();
        }
        int[][] hotSlots = hot;
        for (int slot = 0; slot < slotCount; slot++) {
            int reads = heat[slot];
            heat[slot] = 0;
            long mutations = versions[slot] - seenVersions[slot];
            seenVersions[slot] = versions[slot];
            if (!bit(presentBits, slot) || pinnedAt(slot)) {
                streaks[slot] = 0;
                continue;
            }
            boolean warm = reads >= rules.readsThreshold() && mutations <= rules.maxMutations();
            boolean isHot = hotSlots != null && hotSlots[slot] != null;
            if (!isHot) {
                if (packed[slot] != null && warm) {
                    if (streaks[slot] < Short.MAX_VALUE) streaks[slot]++;
                    if (streaks[slot] >= rules.promoteWindows()) sink.candidate(this, minSectionY + slot, reads);
                } else {
                    streaks[slot] = 0;
                }
            } else if (warm) {
                streaks[slot] = 0;
            } else {
                if (streaks[slot] < Short.MAX_VALUE) streaks[slot]++;
                if (streaks[slot] >= rules.demoteWindows()) sink.idle(this, minSectionY + slot);
            }
        }
    }

    long contentHashAt(int slot) {
        if (bit(hashValid, slot)) return hashes[slot];
        long hash = 0xCBF29CE484222325L;
        int occ = occupied[slot];
        hash = mixHash(hash, occ);
        PackedSection section = packed[slot];
        if (section == null) {
            int state = uniform[slot] & BlockToken.STATE_ID_MASK;
            hash = mixHash(hash, state);
            hash = mixHash(hash, BLOCKS);
        } else {
            char[] indices = HASH_SCRATCH.get();
            section.copyIndices(indices);
            int[] palette = section.palette;
            int runState = palette[indices[0]] & BlockToken.STATE_ID_MASK;
            int runLength = 1;
            for (int i = 1; i < BLOCKS; i++) {
                int state = palette[indices[i]] & BlockToken.STATE_ID_MASK;
                if (state == runState) {
                    runLength++;
                    continue;
                }
                hash = mixHash(hash, runState);
                hash = mixHash(hash, runLength);
                runState = state;
                runLength = 1;
            }
            hash = mixHash(hash, runState);
            hash = mixHash(hash, runLength);
        }
        hashes[slot] = hash;
        setBit(hashValid, slot);
        return hash;
    }

    boolean isHashCachedAt(int slot) {
        return bit(hashValid, slot);
    }

    void setSectionVersion(int slot, long value) {
        versions[slot] = value;
    }

    private static long mixHash(long hash, long value) {
        hash = (hash ^ value) * 0x100000001B3L;
        return hash ^ (hash >>> 29);
    }

    public Object derivedSlotGet(int slotIndex, long generation) {
        DerivedEntry entry = derivedEntry(slotIndex);
        if (entry == null || entry.version() != version || entry.generation() != generation) return null;
        return entry.value();
    }

    public boolean derivedSlotBuilt(int slotIndex) {
        DerivedEntry entry = derivedEntry(slotIndex);
        return entry != null && entry.value() != null;
    }

    public void derivedSlotPut(int slotIndex, long generation, Object value) {
        derivedSlotPut(slotIndex, generation, value, version);
    }

    public void derivedSlotPut(int slotIndex, long generation, Object value, long builtAtVersion) {
        DerivedEntry entry = new DerivedEntry(builtAtVersion, generation, value);
        if (slotIndex >= 0 && slotIndex < DERIVED_SLOT_COUNT) {
            slotsForWrite().set(slotIndex, entry);
            return;
        }
        overflowForWrite().put(slotIndex, entry);
    }

    private DerivedEntry derivedEntry(int slotIndex) {
        if (slotIndex >= 0 && slotIndex < DERIVED_SLOT_COUNT) {
            AtomicReferenceArray<DerivedEntry> slots = derivedSlots;
            return slots == null ? null : slots.get(slotIndex);
        }
        ConcurrentHashMap<Integer, DerivedEntry> overflow = overflowDerivedSlots;
        return overflow == null ? null : overflow.get(slotIndex);
    }

    private AtomicReferenceArray<DerivedEntry> slotsForWrite() {
        AtomicReferenceArray<DerivedEntry> slots = derivedSlots;
        if (slots != null) return slots;
        synchronized (this) {
            if (derivedSlots == null) derivedSlots = new AtomicReferenceArray<>(DERIVED_SLOT_COUNT);
            return derivedSlots;
        }
    }

    private ConcurrentHashMap<Integer, DerivedEntry> overflowForWrite() {
        ConcurrentHashMap<Integer, DerivedEntry> overflow = overflowDerivedSlots;
        if (overflow != null) return overflow;
        synchronized (this) {
            if (overflowDerivedSlots == null) overflowDerivedSlots = new ConcurrentHashMap<>();
            return overflowDerivedSlots;
        }
    }

    public static int chunkXOf(int blockX) {
        return blockX >> 4;
    }

    public static int chunkZOf(int blockZ) {
        return blockZ >> 4;
    }

    public static int sectionYOf(int blockY) {
        return blockY >> 4;
    }

    public static int localOf(int blockCoord) {
        return blockCoord & 15;
    }
}
