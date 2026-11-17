package net.wdc.core.world.storage;

public final class GlobalBlockStateRegistryProvider {

    private static volatile GlobalBlockStateRegistry instance;

    private GlobalBlockStateRegistryProvider() {}

    public static GlobalBlockStateRegistry getOrCreate() {
        GlobalBlockStateRegistry local = instance;
        if (local == null) {
            synchronized (GlobalBlockStateRegistryProvider.class) {
                local = instance;
                if (local == null) {
                    local = new GlobalBlockStateRegistry();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static GlobalBlockStateRegistry peek() {
        return instance;
    }

    public static void reset() {
        instance = null;
    }

    public static BlockStateKey keyOf(int globalId) {
        return getOrCreate().keyOf(globalId);
    }
}
