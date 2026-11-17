package net.wdc.core.core;

import net.wdc.core.world.change.ChangeEntrypoints;
import net.wdc.core.world.change.ModChangeEntrypoints;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WDCServerHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Server");
    private static boolean registered;

    private WDCServerHooks() {}

    static synchronized void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(ServerStartingEvent.class, event -> ServerObserver.onServerStarting(event.getServer(),
                tracker -> ChangeEntrypoints.registerAll(tracker,
                        () -> ModChangeEntrypoints.discover(WDCServerHooks::logChangeEntrypointFailure),
                        WDCServerHooks::logChangeEntrypointFailure)));
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> ServerObserver.onServerStarted(event.getServer()));
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ServerTickEvent.Pre.class,
                event -> ServerObserver.onTickPre(event.getServer()));
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ServerTickEvent.Post.class,
                event -> ServerObserver.onTickPost(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, event -> {
            if (event.getLevel() instanceof ServerLevel serverLevel && event.getChunk() instanceof LevelChunk chunk) {
                ServerObserver.onChunkLoad(serverLevel, chunk);
            }
        });
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class, event -> {
            if (event.getLevel() instanceof ServerLevel serverLevel && event.getChunk() instanceof LevelChunk chunk) {
                ServerObserver.onChunkUnload(serverLevel, chunk);
            }
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> ServerObserver.onServerStopping(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> ServerObserver.onServerStopped());
    }

    private static void logChangeEntrypointFailure(Throwable failure) {
        LOGGER.warn("WorldData Core: a change entrypoint could not be registered", failure);
    }
}
