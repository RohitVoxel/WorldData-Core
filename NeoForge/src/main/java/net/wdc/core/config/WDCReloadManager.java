package net.wdc.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class WDCReloadManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Reload");
    private static final List<WDCReloadable> REGISTERED = new CopyOnWriteArrayList<>();

    private WDCReloadManager() {}

    public static void register(WDCReloadable reloadable) {
        if (reloadable == null) throw new IllegalArgumentException("reloadable must not be null");
        REGISTERED.add(reloadable);
    }

    public static void unregister(WDCReloadable reloadable) {
        REGISTERED.remove(reloadable);
    }

    public static void reloadAll() {
        WDCConfig.load();
        for (WDCReloadable reloadable : REGISTERED) {
            try {
                reloadable.onConfigReload();
            } catch (RuntimeException e) {
                LOGGER.warn("WorldData: reload listener failed — {}", e.getMessage());
            }
        }
        LOGGER.info("WorldData: configuration reloaded ({} systems notified)", REGISTERED.size());
    }
}
