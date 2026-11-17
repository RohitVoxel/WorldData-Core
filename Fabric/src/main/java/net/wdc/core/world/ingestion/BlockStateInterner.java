package net.wdc.core.world.ingestion;

import net.wdc.core.world.material.LiveMaterialResolver;
import net.wdc.core.world.storage.BlockStateKey;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;
import net.wdc.core.world.storage.StateMeta;
import net.minecraft.world.level.block.state.BlockState;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

public final class BlockStateInterner {
    private static final int UNSET = -1;

    private final Reference2IntOpenHashMap<BlockState> cache = newCache();
    private final Reference2IntOpenHashMap<BlockState> tokenCache = newTokenCache();
    private BlockState lastState;
    private int lastId;
    private BlockState lastTokenState;
    private int lastToken;
    private final GlobalBlockStateRegistry registry;
    private final LiveMaterialResolver liveResolver;
    private final boolean useHolder;
    private static boolean holderSeen;

    public static boolean holderPathSeen() {
        return holderSeen;
    }

    public BlockStateInterner(GlobalBlockStateRegistry registry) {
        this(registry, new MinecraftLiveMaterialResolver());
    }

    public BlockStateInterner(GlobalBlockStateRegistry registry, LiveMaterialResolver liveResolver) {
        this(registry, liveResolver, !"false".equalsIgnoreCase(System.getProperty("worlddata.tokenHolder")));
    }

    public BlockStateInterner(GlobalBlockStateRegistry registry, LiveMaterialResolver liveResolver, boolean useHolder) {
        this.registry = registry;
        this.liveResolver = liveResolver;
        this.useHolder = useHolder;
    }

    private static Reference2IntOpenHashMap<BlockState> newCache() {
        Reference2IntOpenHashMap<BlockState> map = new Reference2IntOpenHashMap<>();
        map.defaultReturnValue(GlobalBlockStateRegistry.NULL_ID);
        return map;
    }

    private static Reference2IntOpenHashMap<BlockState> newTokenCache() {
        Reference2IntOpenHashMap<BlockState> map = new Reference2IntOpenHashMap<>();
        map.defaultReturnValue(UNSET);
        return map;
    }

    public boolean usesHolder(BlockState state) {
        return useHolder && state instanceof WDCTokenHolder;
    }

    public int tokenOf(BlockState state) {
        if (state == lastTokenState) return lastToken;
        int token;
        if (useHolder && state instanceof WDCTokenHolder holder) {
            if (!holderSeen) holderSeen = true;
            long packed = holder.wdc$packed();
            int generation = registry.generation();
            if ((int) (packed >>> 32) == generation) {
                token = (int) packed;
            } else {
                token = resolveToken(state);
                holder.wdc$setPacked(((long) generation << 32) | (token & 0xFFFFFFFFL));
            }
        } else {
            token = tokenCache.getInt(state);
            if (token == UNSET) {
                token = resolveToken(state);
                tokenCache.put(state, token);
            }
        }
        lastTokenState = state;
        lastToken = token;
        return token;
    }

    private int resolveToken(BlockState state) {
        return registry.tokenOf(idOf(state));
    }

    public int idOf(BlockState state) {
        if (state == lastState && lastId >= 0) return lastId;
        int cached = cache.getInt(state);
        if (cached >= 0) {
            lastState = state;
            lastId = cached;
            return cached;
        }
        return register(state);
    }

    private int register(BlockState state) {
        BlockStateKey key = BlockStateSnapshots.keyOf(state);
        StateMeta meta = MinecraftStateMeta.describe(state, key);
        int id = registry.registerOrGet(key, meta);
        if (!registry.hasFluidInfo(id)) registry.setFluidInfoOf(id, meta.fluid());
        if (registry.materialOf(id) == null) registry.setMaterialOf(id, meta.material());
        cache.put(state, id);
        lastState = state;
        lastId = id;
        return id;
    }

    public void clear() {
        cache.clear();
        tokenCache.clear();
        lastState = null;
        lastTokenState = null;
    }
}
