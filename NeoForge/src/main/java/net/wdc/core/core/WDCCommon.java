package net.wdc.core.core;

import net.wdc.core.debug.WDCDebugCommands;
import net.wdc.core.debug.monitor.LoaderMods;
import net.wdc.core.world.ingestion.StateMetaRefresh;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(WDCCommon.MOD_ID)
public final class WDCCommon {

    public static final String MOD_ID = "worlddata_core";

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Common");

    public WDCCommon() {
        LoaderMods.install();
        WDCDebugCommands.register();
        WDCServerHooks.register();
        NeoForge.EVENT_BUS.addListener(TagsUpdatedEvent.class, event -> StateMetaRefresh.refresh());
        LOGGER.info("WorldData Core common initializer loaded.");
    }
}
