package net.wdc.core.api.request;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class WDCBlockFilter {

    private static final int MAX_BLOOM_IDS = 16;
    private static final int[] NO_VERDICTS = new int[0];
    private static final int MAX_PROBES = 32;
    private static final AtomicInteger VERDICT_EPOCH = new AtomicInteger();

    private final Set<ResourceLocation> includedIds;
    private final Set<TagKey<Block>> includedTags;
    private final Set<ResourceLocation> excludedIds;
    private final Set<TagKey<Block>> excludedTags;
    private final boolean includeEverythingByDefault;
    private final boolean needsLiveBlockLookup;
    private final Set<String> includedIdStrings;
    private final Set<String> excludedIdStrings;
    private final IntOpenHashSet includedStateIds;
    private final IntOpenHashSet excludedStateIds;
    private final int[] explicitStateIds;
    private volatile int[] verdictTable = NO_VERDICTS;
    private volatile int verdictEpoch = -1;
    private int verdictCount;

    private WDCBlockFilter(Builder b) {
        this.includedIds = Set.copyOf(b.includedIds);
        this.includedTags = Set.copyOf(b.includedTags);
        this.excludedIds = Set.copyOf(b.excludedIds);
        this.excludedTags = Set.copyOf(b.excludedTags);
        this.includedStateIds = new IntOpenHashSet(b.includedStateIds);
        this.excludedStateIds = new IntOpenHashSet(b.excludedStateIds);
        this.includeEverythingByDefault = b.includedIds.isEmpty() && b.includedTags.isEmpty() && b.includedStateIds.isEmpty();
        boolean onlyStateIds = !b.includedStateIds.isEmpty() && b.includedIds.isEmpty() && b.includedTags.isEmpty()
                && b.excludedIds.isEmpty() && b.excludedTags.isEmpty() && b.excludedStateIds.isEmpty()
                && b.includedStateIds.size() <= MAX_BLOOM_IDS;
        this.explicitStateIds = onlyStateIds ? b.includedStateIds.toIntArray() : null;
        this.needsLiveBlockLookup = !this.includedTags.isEmpty() || !this.excludedTags.isEmpty();
        this.includedIdStrings = canonicalStrings(this.includedIds);
        this.excludedIdStrings = canonicalStrings(this.excludedIds);
    }

    private static Set<String> canonicalStrings(Set<ResourceLocation> ids) {
        Set<String> out = new HashSet<>(Math.max(4, ids.size() * 2));
        for (ResourceLocation id : ids) out.add(id.toString());
        return Set.copyOf(out);
    }

    public boolean test(String blockId) {
        return evaluate(blockId, false);
    }

    public boolean testState(int stateId, String blockId) {
        if (!excludedStateIds.isEmpty() && excludedStateIds.contains(stateId)) return false;
        return evaluate(blockId, !includedStateIds.isEmpty() && includedStateIds.contains(stateId));
    }

    public int[] explicitStateIds() {
        return explicitStateIds;
    }

    public static void invalidateVerdicts() {
        VERDICT_EPOCH.incrementAndGet();
    }

    public static int verdictEpoch() {
        return VERDICT_EPOCH.get();
    }

    public boolean testStateCached(int stateId, GlobalBlockStateRegistry registry) {
        if (stateId < 0) return testState(stateId, registry.keyOf(stateId).blockId());
        int epoch = VERDICT_EPOCH.get();
        int[] table = verdictEpoch == epoch ? verdictTable : resetVerdicts(epoch);
        int length = table.length;
        if (length > 0) {
            int wanted = stateId + 1;
            int mask = length - 1;
            int slot = (stateId * 0x9E3779B1) >>> 8 & mask;
            for (int probe = 0; probe < MAX_PROBES; probe++) {
                int entry = table[slot];
                if (entry == 0) break;
                if ((entry >>> 1) == wanted) return (entry & 1) != 0;
                slot = (slot + 1) & mask;
            }
        }
        boolean result = testState(stateId, registry.keyOf(stateId).blockId());
        storeVerdict(stateId, result, epoch);
        return result;
    }

    private synchronized int[] resetVerdicts(int epoch) {
        if (verdictEpoch != epoch) {
            verdictTable = NO_VERDICTS;
            verdictCount = 0;
            verdictEpoch = epoch;
        }
        return verdictTable;
    }

    private synchronized void storeVerdict(int stateId, boolean result, int epoch) {
        if (verdictEpoch != epoch) return;
        int[] table = verdictTable;
        if ((verdictCount + 1) * 2 > table.length) {
            int[] grown = new int[Math.max(16, table.length * 2)];
            int mask = grown.length - 1;
            for (int entry : table) {
                if (entry == 0) continue;
                int slot = (((entry >>> 1) - 1) * 0x9E3779B1) >>> 8 & mask;
                while (grown[slot] != 0) slot = (slot + 1) & mask;
                grown[slot] = entry;
            }
            verdictTable = grown;
            table = grown;
        }
        int wanted = stateId + 1;
        int mask = table.length - 1;
        int slot = (stateId * 0x9E3779B1) >>> 8 & mask;
        while (table[slot] != 0) {
            if ((table[slot] >>> 1) == wanted) return;
            slot = (slot + 1) & mask;
        }
        table[slot] = (wanted << 1) | (result ? 1 : 0);
        verdictCount++;
    }

    private boolean evaluate(String blockId, boolean stateIncluded) {
        if (blockId == null || blockId.isEmpty()) return false;
        if (!needsLiveBlockLookup && blockId.indexOf(':') > 0) {
            if (excludedIdStrings.contains(blockId)) return false;
            if (includeEverythingByDefault || stateIncluded) return true;
            return includedIdStrings.contains(blockId);
        }
        ResourceLocation id = ResourceLocation.parse(blockId);

        for (ResourceLocation excludedId : excludedIds) {
            if (excludedId.equals(id)) return false;
        }

        if (!needsLiveBlockLookup) {
            if (includeEverythingByDefault || stateIncluded) return true;
            for (ResourceLocation includedId : includedIds) {
                if (includedId.equals(id)) return true;
            }
            return false;
        }

        if (!BuiltInRegistries.BLOCK.containsKey(id)) return stateIncluded;
        Block block = BuiltInRegistries.BLOCK.get(id);

        for (TagKey<Block> excludedTag : excludedTags) {
            if (block.builtInRegistryHolder().is(excludedTag)) return false;
        }

        if (includeEverythingByDefault || stateIncluded) return true;

        for (ResourceLocation includedId : includedIds) {
            if (includedId.equals(id)) return true;
        }
        for (TagKey<Block> includedTag : includedTags) {
            if (block.builtInRegistryHolder().is(includedTag)) return true;
        }
        return false;
    }

    public static Builder builder() { return new Builder(); }

    public static WDCBlockFilter ofId(String blockId) {
        return builder().id(blockId).build();
    }

    public static WDCBlockFilter ofTag(String tagId) {
        return builder().tag(tagId).build();
    }

    public static WDCBlockFilter ofStateId(int... stateIds) {
        return builder().stateId(stateIds).build();
    }

    public static final class Builder {
        private final Set<ResourceLocation> includedIds = new LinkedHashSet<>();
        private final Set<TagKey<Block>> includedTags = new LinkedHashSet<>();
        private final Set<ResourceLocation> excludedIds = new LinkedHashSet<>();
        private final Set<TagKey<Block>> excludedTags = new LinkedHashSet<>();
        private final IntOpenHashSet includedStateIds = new IntOpenHashSet();
        private final IntOpenHashSet excludedStateIds = new IntOpenHashSet();

        private Builder() {}

        public Builder stateId(int... stateIds) {
            if (stateIds == null) throw new IllegalArgumentException("stateIds must not be null");
            for (int stateId : stateIds) {
                if (stateId < 0) throw new IllegalArgumentException("stateId must not be negative: " + stateId);
                includedStateIds.add(stateId);
            }
            return this;
        }

        public Builder excludeStateId(int... stateIds) {
            if (stateIds == null) throw new IllegalArgumentException("stateIds must not be null");
            for (int stateId : stateIds) {
                if (stateId < 0) throw new IllegalArgumentException("stateId must not be negative: " + stateId);
                excludedStateIds.add(stateId);
            }
            return this;
        }

        public Builder id(String blockId) {
            includedIds.add(parse(blockId));
            return this;
        }

        public Builder tag(String tagId) {
            includedTags.add(TagKey.create(net.minecraft.core.registries.Registries.BLOCK, parse(tagId)));
            return this;
        }

        public Builder exclude(String blockId) {
            excludedIds.add(parse(blockId));
            return this;
        }

        public Builder excludeTag(String tagId) {
            excludedTags.add(TagKey.create(net.minecraft.core.registries.Registries.BLOCK, parse(tagId)));
            return this;
        }

        public WDCBlockFilter build() { return new WDCBlockFilter(this); }

        private static ResourceLocation parse(String value) {
            if (value == null || value.isEmpty()) throw new IllegalArgumentException("value must not be null or empty");
            return ResourceLocation.parse(value);
        }
    }
}
