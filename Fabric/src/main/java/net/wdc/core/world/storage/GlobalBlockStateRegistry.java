package net.wdc.core.world.storage;

import net.wdc.core.world.material.WDCMaterial;
import net.wdc.core.world.material.WDCMaterialClassifier;
import net.wdc.core.world.material.WDCFluidInfo;
import net.wdc.core.world.material.WDCFluidState;
import net.wdc.core.world.material.WDCMaterialTag;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public final class GlobalBlockStateRegistry {

    public static final int NULL_ID = -1;
    public static final String DEFAULT_AIR_ID = "minecraft:air";

    private static final AtomicInteger GENERATIONS = new AtomicInteger();

    private final Map<BlockStateKey, Integer> keyToId = new ConcurrentHashMap<>();
    private volatile BlockStateKey[] idToKey = new BlockStateKey[64];
    private volatile WDCMaterialTag[] idToMaterial = new WDCMaterialTag[64];
    private volatile WDCFluidInfo[] idToFluid = new WDCFluidInfo[64];
    private final ReentrantLock registerLock = new ReentrantLock();
    private volatile int size = 0;
    private volatile int metaEpoch;
    private volatile Object scanHandle;
    private volatile CachedInfo[] blockInfoCache = new CachedInfo[64];
    private volatile CachedInfo[] waterInfoCache = new CachedInfo[64];
    private volatile int[] idToToken = new int[64];
    private volatile long[] idToDescriptor = new long[64];
    private final Map<String, IntArrayList> statesByBlock = new HashMap<>();
    private final Map<String, int[]> statesArrays = new ConcurrentHashMap<>();
    private static final int[] NO_STATES = new int[0];
    private final int generation = GENERATIONS.incrementAndGet();
    private volatile StateMetaResolver metaResolver;
    private volatile int defaultAirId = NULL_ID;

    private record CachedInfo(int epoch, Object value) {}

    public int registerOrGet(BlockStateKey key) {
        if (key == null) throw new IllegalArgumentException("key must not be null");

        Integer existing = keyToId.get(key);
        if (existing != null) return existing;

        StateMeta meta = resolveMeta(key);
        return register(key, meta, false);
    }

    public int registerOrGet(BlockStateKey key, StateMeta meta) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        if (meta == null) throw new IllegalArgumentException("meta must not be null");

        Integer existing = keyToId.get(key);
        if (existing != null) return existing;

        return register(key, meta, true);
    }

    private StateMeta resolveMeta(BlockStateKey key) {
        StateMetaResolver resolver = metaResolver;
        if (resolver != null) {
            StateMeta resolved = resolver.resolve(key);
            if (resolved != null) return resolved;
        }
        return StateMeta.fromKey(key);
    }

    private int register(BlockStateKey key, StateMeta meta, boolean storeObjects) {
        registerLock.lock();
        try {
            Integer existing = keyToId.get(key);
            if (existing != null) return existing;

            int id = size;
            if (id >= BlockToken.MAX_STATES) throw new IllegalStateException("global state id space exhausted");
            ensureCapacity(id + 1);
            idToKey[id] = key;
            idToToken[id] = tokenFor(id, key, meta);
            idToDescriptor[id] = StateDescriptor.pack(meta.material().material(), meta.tagMask(), meta.fluid(),
                    meta.lightEmission(), meta.lightOpacity());
            if (storeObjects) {
                idToMaterial[id] = meta.material();
                idToFluid[id] = meta.fluid();
            }
            IntArrayList blockStates = statesByBlock.computeIfAbsent(key.blockId(), k -> new IntArrayList(4));
            blockStates.add(id);
            size = id + 1;
            keyToId.put(key, id);
            statesArrays.put(key.blockId(), blockStates.toIntArray());
            return id;
        } finally {
            registerLock.unlock();
        }
    }

    private static int tokenFor(int id, BlockStateKey key, StateMeta meta) {
        if (isDefaultAirKey(key)) return BlockToken.AIR;
        if (meta.material().material() != WDCMaterial.AIR) return BlockToken.make(id, meta.flags());
        return BlockToken.makeAirLike(id, meta.flags());
    }

    public static boolean isDefaultAirKey(BlockStateKey key) {
        return key != null && !key.hasProperties() && DEFAULT_AIR_ID.equals(key.blockId());
    }

    public int defaultAirId() {
        int id = defaultAirId;
        if (id != NULL_ID) return id;
        id = registerOrGet(BlockStateKey.of(DEFAULT_AIR_ID));
        defaultAirId = id;
        return id;
    }

    public int exactIdOf(int token) {
        return BlockToken.isDefaultAir(token) ? defaultAirId() : BlockToken.globalIdOf(token);
    }

    public void setMetaResolver(StateMetaResolver resolver) {
        this.metaResolver = resolver;
    }

    public int generation() {
        return generation;
    }

    public int tokenOf(int id) {
        int[] snapshot = idToToken;
        return id >= 0 && id < snapshot.length && id < size ? snapshot[id] : BlockToken.AIR;
    }

    public int[] tokenTable() {
        return idToToken;
    }

    public long descriptorOf(int id) {
        long[] snapshot = idToDescriptor;
        return id >= 0 && id < snapshot.length && id < size ? snapshot[id] : 0L;
    }

    public long[] descriptorTable() {
        return idToDescriptor;
    }

    public int tagMaskOf(int id) {
        return StateDescriptor.tagMask(descriptorOf(id));
    }

    public int[] statesOfBlock(String blockId) {
        int[] shared = sharedStatesOfBlock(blockId);
        return shared.length == 0 ? shared : shared.clone();
    }

    public int[] sharedStatesOfBlock(String blockId) {
        if (blockId == null) return NO_STATES;
        int[] ids = statesArrays.get(blockId);
        return ids == null ? NO_STATES : ids;
    }

    public List<String> registeredBlockIds() {
        registerLock.lock();
        try {
            return new ArrayList<>(statesByBlock.keySet());
        } finally {
            registerLock.unlock();
        }
    }

    public void refreshMeta(int id, StateMeta meta) {
        if (meta == null || id < 0) return;
        registerLock.lock();
        try {
            if (id >= size) return;
            idToDescriptor[id] = StateDescriptor.pack(meta.material().material(), meta.tagMask(), meta.fluid(),
                    meta.lightEmission(), meta.lightOpacity());
            idToToken[id] = tokenFor(id, idToKey[id], meta);
            idToMaterial[id] = meta.material();
            idToFluid[id] = meta.fluid();
            metaEpoch++;
        } finally {
            registerLock.unlock();
        }
    }

    public void setMaterialOf(int id, WDCMaterialTag tag) {
        if (tag == null || id < 0) return;
        registerLock.lock();
        try {
            if (id >= size) return;
            idToMaterial[id] = tag;
            long descriptor = idToDescriptor[id];
            idToDescriptor[id] = (descriptor & ~0xFFFFL) | (tag.material().ordinal() & 0xFFFFL);
            metaEpoch++;
        } finally {
            registerLock.unlock();
        }
    }

    public void setFluidInfoOf(int id, WDCFluidInfo info) {
        if (info == null || id < 0) return;
        registerLock.lock();
        try {
            if (id >= size) return;
            idToFluid[id] = info;
            metaEpoch++;
        } finally {
            registerLock.unlock();
        }
    }

    public int metaEpoch() {
        return metaEpoch;
    }

    public Object scanHandle() {
        return scanHandle;
    }

    public void setScanHandle(Object handle) {
        this.scanHandle = handle;
    }

    public Object cachedBlockInfo(int id) {
        return cachedInfo(blockInfoCache, id);
    }

    public Object cachedWaterInfo(int id) {
        return cachedInfo(waterInfoCache, id);
    }

    public void cacheBlockInfo(int id, int epoch, Object info) {
        blockInfoCache = storeInfo(blockInfoCache, id, epoch, info);
    }

    public void cacheWaterInfo(int id, int epoch, Object info) {
        waterInfoCache = storeInfo(waterInfoCache, id, epoch, info);
    }

    private Object cachedInfo(CachedInfo[] cache, int id) {
        if (id < 0 || id >= cache.length) return null;
        CachedInfo entry = cache[id];
        return entry != null && entry.epoch == metaEpoch ? entry.value : null;
    }

    private static CachedInfo[] storeInfo(CachedInfo[] cache, int id, int epoch, Object info) {
        if (id < 0 || info == null) return cache;
        CachedInfo[] target = cache;
        if (id >= target.length) target = java.util.Arrays.copyOf(target, Math.max(id + 1, target.length * 2));
        target[id] = new CachedInfo(epoch, info);
        return target;
    }

    public boolean hasFluidInfo(int id) {
        WDCFluidInfo[] snapshot = idToFluid;
        return id >= 0 && id < snapshot.length && snapshot[id] != null;
    }

    public WDCFluidInfo fluidInfoOf(int id) {
        WDCFluidInfo[] snapshot = idToFluid;
        if (id >= 0 && id < snapshot.length && snapshot[id] != null) return snapshot[id];
        if (id < 0 || id >= size) return WDCFluidInfo.NONE;
        long descriptor = descriptorOf(id);
        if (StateDescriptor.fluidKind(descriptor) != 0) return StateDescriptor.fluid(descriptor);
        WDCFluidInfo derived = WDCFluidState.infoOf(keyOf(id));
        if (derived.present()) return derived;
        WDCMaterialTag tag = materialOf(id);
        return tag != null && tag.isFluid() ? WDCFluidInfo.UNKNOWN_FLUID : WDCFluidInfo.NONE;
    }

    public WDCMaterialTag materialOf(int id) {
        WDCMaterialTag[] snapshot = idToMaterial;
        if (id < 0 || id >= snapshot.length) return null;
        return snapshot[id];
    }

    public WDCMaterialTag materialTagOf(int id) {
        WDCMaterialTag tag = materialOf(id);
        if (tag != null) return tag;
        if (id < 0 || id >= size) return WDCMaterialTag.of(WDCMaterial.UNKNOWN);
        return WDCMaterialTag.of(WDCMaterialClassifier.classify(keyOf(id).blockId()));
    }

    public BlockStateKey keyOf(int id) {
        BlockStateKey[] snapshot = idToKey;
        if (id < 0 || id >= size || id >= snapshot.length) {
            throw new IllegalArgumentException("unknown GlobalBlockStateId: " + id);
        }
        BlockStateKey key = snapshot[id];
        if (key == null) throw new IllegalArgumentException("unknown GlobalBlockStateId: " + id);
        return key;
    }

    public int idOf(BlockStateKey key) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        Integer id = keyToId.get(key);
        if (id == null) throw new IllegalArgumentException("key is not registered: " + key.asString());
        return id;
    }

    public int findId(BlockStateKey key) {
        if (key == null) return NULL_ID;
        Integer id = keyToId.get(key);
        return id == null ? NULL_ID : id;
    }

    public boolean contains(BlockStateKey key) {
        return key != null && keyToId.containsKey(key);
    }

    public int size() {
        return size;
    }

    public List<BlockStateKey> snapshotKeys() {
        BlockStateKey[] snapshot = idToKey;
        int n = size;
        List<BlockStateKey> out = new ArrayList<>(n);
        Collections.addAll(out, Arrays.copyOf(snapshot, n));
        return out;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity <= idToKey.length) return;
        int newCapacity = Math.max(minCapacity, idToKey.length * 2);
        BlockStateKey[] grownKeys = new BlockStateKey[newCapacity];
        System.arraycopy(idToKey, 0, grownKeys, 0, size);
        idToKey = grownKeys;

        WDCMaterialTag[] grownMaterial = new WDCMaterialTag[newCapacity];
        System.arraycopy(idToMaterial, 0, grownMaterial, 0, size);
        idToMaterial = grownMaterial;

        WDCFluidInfo[] grownFluid = new WDCFluidInfo[newCapacity];
        System.arraycopy(idToFluid, 0, grownFluid, 0, size);
        idToFluid = grownFluid;

        int[] grownTokens = new int[newCapacity];
        System.arraycopy(idToToken, 0, grownTokens, 0, size);
        idToToken = grownTokens;

        long[] grownDescriptors = new long[newCapacity];
        System.arraycopy(idToDescriptor, 0, grownDescriptors, 0, size);
        idToDescriptor = grownDescriptors;
    }
}
