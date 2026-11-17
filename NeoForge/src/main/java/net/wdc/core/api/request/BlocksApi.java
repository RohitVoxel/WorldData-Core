package net.wdc.core.api.request;

import net.wdc.core.debug.monitor.MonitorApi;
import net.wdc.core.debug.monitor.WDCApiMonitor;
import net.wdc.core.world.storage.BlockStateKey;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BlocksApi {

    private static final int[] NONE = new int[0];
    private static final int CACHE_LIMIT = 4096;

    private record TagStates(int registrySize, int epoch, int[] ids) {}

    private final GlobalBlockStateRegistry registry;
    private final ConcurrentHashMap<String, String> canonicalIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> plainIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> missingAtSize = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TagStates> tagStates = new ConcurrentHashMap<>();

    public BlocksApi(GlobalBlockStateRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        this.registry = registry;
    }

    public int stateCount() {
        return registry.size();
    }

    public int[] stateIdsOfBlock(String blockId) {
        if (WDCApiMonitor.enabled) WDCApiMonitor.hit(MonitorApi.STATE_IDS_OF_BLOCK, WDCApiMonitor.textKey(blockId));
        if (blockId == null || blockId.isEmpty()) return NONE;
        String canonical = canonical(blockId);
        if (canonical == null) return NONE;
        int[] shared = registry.sharedStatesOfBlock(canonical);
        return shared.length == 0 ? NONE : shared.clone();
    }

    public int[] stateIdsOfTag(String tagId) {
        if (WDCApiMonitor.enabled) WDCApiMonitor.hit(MonitorApi.STATE_IDS_OF_TAG, WDCApiMonitor.textKey(tagId));
        if (tagId == null || tagId.isEmpty()) return NONE;
        int epoch = WDCBlockFilter.verdictEpoch();
        int size = registry.size();
        TagStates cached = tagStates.get(tagId);
        if (cached != null && cached.registrySize == size && cached.epoch == epoch) {
            return cached.ids.length == 0 ? NONE : cached.ids.clone();
        }
        ResourceLocation tagLocation = ResourceLocation.tryParse(tagId);
        if (tagLocation == null) return NONE;
        TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagLocation);
        int[] ids = NONE;
        for (String blockId : registry.registeredBlockIds()) {
            ResourceLocation id = ResourceLocation.tryParse(blockId);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) continue;
            Block block = BuiltInRegistries.BLOCK.get(id);
            if (block == null || !block.builtInRegistryHolder().is(tag)) continue;
            int[] states = registry.sharedStatesOfBlock(blockId);
            if (states.length == 0) continue;
            int[] merged = Arrays.copyOf(ids, ids.length + states.length);
            System.arraycopy(states, 0, merged, ids.length, states.length);
            ids = merged;
        }
        Arrays.sort(ids);
        if (tagStates.size() >= CACHE_LIMIT) tagStates.clear();
        tagStates.put(tagId, new TagStates(size, epoch, ids));
        return ids.length == 0 ? NONE : ids.clone();
    }

    public int stateIdOf(String blockId) {
        if (WDCApiMonitor.enabled) WDCApiMonitor.hit(MonitorApi.STATE_ID_OF, WDCApiMonitor.textKey(blockId));
        if (blockId == null || blockId.isEmpty()) return GlobalBlockStateRegistry.NULL_ID;
        Integer known = plainIds.get(blockId);
        if (known != null) return known;
        int size = registry.size();
        Integer missing = missingAtSize.get(blockId);
        if (missing != null && missing == size) return GlobalBlockStateRegistry.NULL_ID;
        String canonical = canonical(blockId);
        if (canonical == null) return GlobalBlockStateRegistry.NULL_ID;
        int found = registry.findId(BlockStateKey.of(canonical));
        if (found == GlobalBlockStateRegistry.NULL_ID) {
            if (missingAtSize.size() >= CACHE_LIMIT) missingAtSize.clear();
            missingAtSize.put(blockId, size);
            return found;
        }
        if (plainIds.size() >= CACHE_LIMIT) plainIds.clear();
        plainIds.put(blockId, found);
        return found;
    }

    public int stateIdOf(String blockId, Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) return stateIdOf(blockId);
        if (WDCApiMonitor.enabled) WDCApiMonitor.hit(MonitorApi.STATE_ID_OF, WDCApiMonitor.mixKey(WDCApiMonitor.textKey(blockId), properties.hashCode()));
        if (blockId == null || blockId.isEmpty()) return GlobalBlockStateRegistry.NULL_ID;
        String canonical = canonical(blockId);
        if (canonical == null) return GlobalBlockStateRegistry.NULL_ID;
        return registry.findId(BlockStateKey.of(canonical, properties));
    }

    public WDCBlockInfo info(int stateId) {
        if (WDCApiMonitor.enabled) WDCApiMonitor.hit(MonitorApi.BLOCK_INFO, stateId);
        if (stateId < 0 || stateId >= registry.size()) return WDCBlockInfo.ABSENT;
        return WDCBlockInfos.of(registry, stateId);
    }

    public String blockIdOf(int stateId) {
        if (WDCApiMonitor.enabled) WDCApiMonitor.hit(MonitorApi.BLOCK_ID_OF, stateId);
        if (stateId < 0 || stateId >= registry.size()) return null;
        return registry.keyOf(stateId).blockId();
    }

    public Map<String, String> propertiesOf(int stateId) {
        if (WDCApiMonitor.enabled) WDCApiMonitor.hit(MonitorApi.PROPERTIES_OF, stateId);
        if (stateId < 0 || stateId >= registry.size()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(registry.keyOf(stateId).properties()));
    }

    public boolean hasTag(int stateId, WDCStateTag tag) {
        if (WDCApiMonitor.enabled) WDCApiMonitor.hit(MonitorApi.HAS_TAG, WDCApiMonitor.mixKey(stateId, tag == null ? -1L : tag.ordinal()));
        if (tag == null || stateId < 0 || stateId >= registry.size()) return false;
        return (registry.tagMaskOf(stateId) & tag.bit()) != 0;
    }

    public WDCBlockFilter filterOfStateIds(int... stateIds) {
        return WDCBlockFilter.ofStateId(stateIds);
    }

    private String canonical(String blockId) {
        String cached = canonicalIds.get(blockId);
        if (cached != null) return cached;
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null) return null;
        String canonical = id.toString();
        if (canonicalIds.size() >= CACHE_LIMIT) canonicalIds.clear();
        canonicalIds.put(blockId, canonical);
        return canonical;
    }
}
