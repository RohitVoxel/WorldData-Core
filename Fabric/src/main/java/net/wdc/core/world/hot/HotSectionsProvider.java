package net.wdc.core.world.hot;

import net.wdc.core.config.HotSectionsConfig;
import net.wdc.core.world.cache.WDCCache;

public final class HotSectionsProvider {

    private static volatile HotSections instance;

    private HotSectionsProvider() {}

    public static HotSections getOrCreate(WDCCache cache, HotSectionsConfig config) {
        HotSections local = instance;
        if (local == null) {
            synchronized (HotSectionsProvider.class) {
                local = instance;
                if (local == null) {
                    local = new HotSections(cache, config);
                    instance = local;
                }
            }
        }
        return local;
    }

    public static HotSections get() {
        return instance;
    }

    public static void reset() {
        instance = null;
    }
}
