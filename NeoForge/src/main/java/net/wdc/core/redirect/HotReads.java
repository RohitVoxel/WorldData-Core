package net.wdc.core.redirect;

import net.wdc.core.world.ingestion.IngestionBridge;
import net.wdc.core.world.ingestion.MinecraftStateMeta;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;
import net.wdc.core.world.storage.GlobalBlockStateRegistryProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.Arrays;

public final class HotReads {

    private static volatile BlockState[] states = new BlockState[256];
    private static volatile GlobalBlockStateRegistry owner;

    private HotReads() {}

    public static BlockState blockState(Level level, BlockPos pos) {
        return resolve(ReadRedirect.Api.BLOCK_STATE, level, pos);
    }

    public static FluidState fluidState(Level level, BlockPos pos) {
        BlockState state = resolve(ReadRedirect.Api.FLUID_STATE, level, pos);
        return state == null ? null : state.getFluidState();
    }

    private static BlockState resolve(ReadRedirect.Api api, Level level, BlockPos pos) {
        int id = ReadRedirect.stateIdAt(api, level.isClientSide(), IngestionBridge.dimensionName(level.dimension()),
                pos.getX(), pos.getY(), pos.getZ());
        if (id == ReadRedirect.MISS) return null;
        BlockState state = stateOf(id);
        if (state == null) ReadRedirect.unresolved(api);
        return state;
    }

    private static BlockState stateOf(int id) {
        GlobalBlockStateRegistry registry = GlobalBlockStateRegistryProvider.getOrCreate();
        if (registry != owner) {
            synchronized (HotReads.class) {
                if (registry != owner) {
                    states = new BlockState[256];
                    owner = registry;
                }
            }
        }
        BlockState[] table = states;
        if (id < table.length) {
            BlockState cached = table[id];
            if (cached != null) return cached;
        }
        if (id >= registry.size()) return null;
        BlockState resolved = MinecraftStateMeta.stateOf(registry.keyOf(id));
        if (resolved == null) return null;
        synchronized (HotReads.class) {
            BlockState[] current = states;
            if (id >= current.length) current = Arrays.copyOf(current, Math.max(id + 1, current.length * 2));
            current[id] = resolved;
            states = current;
        }
        return resolved;
    }

    public static void reset() {
        states = new BlockState[256];
        owner = null;
    }
}
