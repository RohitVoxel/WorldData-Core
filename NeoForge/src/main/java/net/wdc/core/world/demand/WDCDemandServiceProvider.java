package net.wdc.core.world.demand;

import net.wdc.core.world.cache.WDCCache;

public final class WDCDemandServiceProvider {

    private static volatile WDCDemandService instance;

    private WDCDemandServiceProvider() {}

    public static WDCDemandService getOrCreate(WDCCache cache) {
        WDCDemandService local = instance;
        if (local == null) {
            synchronized (WDCDemandServiceProvider.class) {
                local = instance;
                if (local == null) {
                    local = new WDCDemandService(cache);
                    instance = local;
                }
            }
        }
        return local;
    }

    public static WDCDemandService getIfPresent() {
        return instance;
    }

    public static void reset() {
        instance = null;
    }
}
