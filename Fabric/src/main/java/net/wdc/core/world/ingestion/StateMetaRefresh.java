package net.wdc.core.world.ingestion;

import net.wdc.core.world.storage.GlobalBlockStateRegistry;
import net.wdc.core.world.storage.GlobalBlockStateRegistryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StateMetaRefresh {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Classify");

    private StateMetaRefresh() {}

    public static void refresh() {
        net.wdc.core.api.request.WDCBlockFilter.invalidateVerdicts();
        GlobalBlockStateRegistry registry = GlobalBlockStateRegistryProvider.peek();
        if (registry == null) return;
        try {
            int refreshed = MinecraftStateMeta.refreshAll(registry);
            if (refreshed > 0) LOGGER.info("Re-classified {} block states after a tag update", refreshed);
        } catch (RuntimeException failure) {
            LOGGER.warn("Block state re-classification failed: {}", failure.toString());
        }
    }
}
