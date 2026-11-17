package net.wdc.core.core;

import net.wdc.core.api.WDCTickPhase;
import net.wdc.core.api.changes.WDCChangeEntrypoint;
import net.wdc.core.world.change.ChangeEntrypoints;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WDCServerObserver implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Server");

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> ServerObserver.onServerStarting(server,
                tracker -> ChangeEntrypoints.registerAll(tracker,
                        () -> FabricLoader.getInstance().getEntrypoints(WDCChangeEntrypoint.ENTRYPOINT_KEY, WDCChangeEntrypoint.class),
                        WDCServerObserver::logChangeEntrypointFailure)));
        ServerLifecycleEvents.SERVER_STARTED.register(ServerObserver::onServerStarted);
        ServerTickEvents.START_SERVER_TICK.addPhaseOrdering(WDCTickPhase.BEGIN, Event.DEFAULT_PHASE);
        ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, WDCTickPhase.DRAIN);
        ServerTickEvents.START_SERVER_TICK.register(WDCTickPhase.BEGIN, ServerObserver::onTickPre);
        ServerTickEvents.END_SERVER_TICK.register(WDCTickPhase.DRAIN, ServerObserver::onTickPost);
        ServerChunkEvents.CHUNK_LOAD.register(ServerObserver::onChunkLoad);
        ServerChunkEvents.CHUNK_UNLOAD.register(ServerObserver::onChunkUnload);
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerObserver::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerObserver.onServerStopped());
    }

    private static void logChangeEntrypointFailure(Throwable failure) {
        LOGGER.warn("WorldData Core: a change entrypoint could not be registered", failure);
    }
}
