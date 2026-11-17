package net.wdc.core.core;

import net.wdc.core.debug.WDCDebugCommands;
import net.wdc.core.debug.monitor.LoaderMods;
import net.wdc.core.world.ingestion.StateMetaRefresh;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WDCCommon implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Common");

    @Override
    public void onInitialize() {
        LoaderMods.install();
        WDCDebugCommands.register();
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> StateMetaRefresh.refresh());
        LOGGER.info("WorldData Core common initializer loaded.");
    }
}
