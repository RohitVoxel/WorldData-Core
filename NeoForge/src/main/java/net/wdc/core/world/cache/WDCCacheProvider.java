package net.wdc.core.world.cache;

public final class WDCCacheProvider {

    private static volatile WDCCache instance;

    private WDCCacheProvider() {}

    public static WDCCache getOrCreate(ChunkFingerprintSource fingerprintSource) {
        WDCCache local = instance;
        if (local == null) {
            synchronized (WDCCacheProvider.class) {
                local = instance;
                if (local == null) {
                    local = new WDCCache(fingerprintSource);
                    instance = local;
                }
            }
        }
        return local;
    }

    public static WDCCache get() {
        return instance;
    }

    public static WDCCache getIfPresent() {
        return instance;
    }

    public static void reset() {
        instance = null;
    }
}
