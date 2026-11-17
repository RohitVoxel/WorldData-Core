package net.wdc.core.api.request;

import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.WDCSection;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;
import net.wdc.core.world.storage.PackedPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class WDCBlockQuery {
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final GlobalBlockStateRegistry registry;

    public WDCBlockQuery(GlobalBlockStateRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        this.registry = registry;
    }

    public List<WDCBlockMatch> find(WDCChunk chunk, WDCBlockFilter filter) {
        if (chunk == null || filter == null) throw new IllegalArgumentException("chunk/filter must not be null");
        Scratch scratch = SCRATCH.get();
        WDCSection.EntryTest test = scratch.test(filter, registry);
        List<WDCBlockMatch> out = null;
        int baseX = chunk.chunkX() << 4;
        int baseZ = chunk.chunkZ() << 4;
        for (WDCSection section : chunk.sectionArray()) {
            if (!mightMatch(section, filter)) continue;
            int found = select(section, test, scratch);
            if (found == 0) continue;
            if (out == null) out = new ArrayList<>(Math.max(found, 16));
            int baseY = section.sectionY() << 4;
            char[] positions = scratch.positions;
            char[] slots = scratch.slots;
            String[] names = scratch.names;
            for (int i = 0; i < found; i++) {
                int packed = positions[i];
                out.add(new WDCBlockMatch(baseX + (packed & 15), baseY + (packed >>> 8), baseZ + ((packed >>> 4) & 15), names[slots[i]]));
            }
            scratch.release(section.stateEntryLimit() + 1);
        }
        return out == null ? new ArrayList<>(0) : out;
    }

    public void visit(WDCChunk chunk, WDCBlockFilter filter, BlockVisitor visitor) {
        if (chunk == null || filter == null || visitor == null) throw new IllegalArgumentException("chunk/filter/visitor must not be null");
        Scratch scratch = SCRATCH.get();
        WDCSection.EntryTest test = scratch.test(filter, registry);
        int baseX = chunk.chunkX() << 4;
        int baseZ = chunk.chunkZ() << 4;
        for (WDCSection section : chunk.sectionArray()) {
            if (!mightMatch(section, filter)) continue;
            int found = select(section, test, scratch);
            if (found == 0) continue;
            int baseY = section.sectionY() << 4;
            char[] positions = scratch.positions;
            char[] slots = scratch.slots;
            String[] names = scratch.names;
            int[] ids = scratch.ids;
            boolean keepGoing = true;
            for (int i = 0; i < found && keepGoing; i++) {
                int packed = positions[i];
                int slot = slots[i];
                keepGoing = visitor.visit(baseX + (packed & 15), baseY + (packed >>> 8), baseZ + ((packed >>> 4) & 15), ids[slot], names[slot]);
            }
            scratch.release(section.stateEntryLimit() + 1);
            if (!keepGoing) return;
        }
    }

    public boolean any(WDCChunk chunk, WDCBlockFilter filter) {
        if (chunk == null || filter == null) throw new IllegalArgumentException("chunk/filter must not be null");
        WDCSection.EntryTest test = SCRATCH.get().test(filter, registry);
        for (WDCSection section : chunk.sectionArray()) {
            if (!mightMatch(section, filter)) continue;
            if (section.anyMatching(test)) return true;
        }
        return false;
    }

    public int count(WDCChunk chunk, WDCBlockFilter filter) {
        if (chunk == null || filter == null) throw new IllegalArgumentException("chunk/filter must not be null");
        WDCSection.EntryTest test = SCRATCH.get().test(filter, registry);
        int total = 0;
        for (WDCSection section : chunk.sectionArray()) {
            if (!mightMatch(section, filter)) continue;
            total += section.countMatching(test);
        }
        return total;
    }

    private int select(WDCSection section, WDCSection.EntryTest test, Scratch scratch) {
        int limit = section.stateEntryLimit();
        scratch.ensure(limit + 1);
        int matched = section.markMatching(test, scratch.match, scratch.ids);
        if (matched == 0) return 0;
        boolean[] match = scratch.match;
        for (int slot = 1; slot <= limit; slot++) {
            if (match[slot]) scratch.names[slot] = registry.keyOf(scratch.ids[slot]).blockId();
        }
        int found = section.collectPositionsAndSlots(match, 0, 4096, scratch.positions, scratch.slots);
        if (found == 0) scratch.release(limit + 1);
        return found;
    }

    private static boolean mightMatch(WDCSection section, WDCBlockFilter filter) {
        int[] ids = filter.explicitStateIds();
        if (ids == null) return true;
        for (int id : ids) {
            if (section.mightContainState(id)) return true;
        }
        return false;
    }

    private static final class VerdictTest implements WDCSection.EntryTest {
        WDCBlockFilter filter;
        GlobalBlockStateRegistry registry;

        @Override
        public boolean accept(int globalStateId) {
            return filter.testStateCached(globalStateId, registry);
        }
    }

    private static final class Scratch {
        boolean[] match = new boolean[16];
        int[] ids = new int[16];
        String[] names = new String[16];
        final char[] positions = new char[4096];
        final char[] slots = new char[4096];
        private final VerdictTest verdict = new VerdictTest();

        WDCSection.EntryTest test(WDCBlockFilter filter, GlobalBlockStateRegistry registry) {
            verdict.filter = filter;
            verdict.registry = registry;
            return verdict;
        }

        void ensure(int size) {
            if (match.length >= size) return;
            int grown = Math.max(size, match.length * 2);
            match = Arrays.copyOf(match, grown);
            ids = Arrays.copyOf(ids, grown);
            names = Arrays.copyOf(names, grown);
        }

        void release(int size) {
            Arrays.fill(match, 0, Math.min(size, match.length), false);
            Arrays.fill(names, 0, Math.min(size, names.length), null);
        }
    }
}
