package net.wdc.core.redirect;

import net.wdc.core.config.HotSectionsConfig;
import net.wdc.core.world.ingestion.IngestionBridge;
import net.wdc.core.world.storage.BlockToken;
import net.wdc.core.world.storage.WDCChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

public final class ReadRedirect {

    public enum Api { BLOCK_STATE, FLUID_STATE }

    public static final int MISS = -1;

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Redirect");
    private static final AtomicBoolean STARTUP_APPLIED = new AtomicBoolean();
    private static final LongAdder[] SERVED = adders();
    private static final LongAdder[] FALLBACK = adders();
    private static final LongAdder TOGGLES = new LongAdder();
    private static volatile RedirectCounts baseline = RedirectCounts.ZERO;
    private static volatile boolean enabled;

    private ReadRedirect() {}

    private static LongAdder[] adders() {
        LongAdder[] out = new LongAdder[Api.values().length];
        for (int i = 0; i < out.length; i++) out[i] = new LongAdder();
        return out;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static boolean active() {
        return enabled && !RedirectGuard.isInternal();
    }

    public static boolean set(boolean on, String reason) {
        boolean previous = enabled;
        enabled = on;
        boolean changed = previous != on;
        if (changed) {
            TOGGLES.increment();
            resetCounters();
        }
        LOGGER.info("WorldData: hot-read redirect {} ({})", on ? "enabled" : "disabled", reason);
        return changed;
    }

    public static void applyStartup(HotSectionsConfig config) {
        if (config == null || !STARTUP_APPLIED.compareAndSet(false, true)) return;
        if (config.redirectReads) set(true, "redirectReads is true in worlddata_core.json");
    }

    public static long served() {
        return counts().served();
    }

    public static long fellThrough() {
        return counts().fallback();
    }

    public static long served(Api api) {
        RedirectCounts counts = counts();
        return api == Api.BLOCK_STATE ? counts.blockServed() : counts.fluidServed();
    }

    public static long fellThrough(Api api) {
        RedirectCounts counts = counts();
        return api == Api.BLOCK_STATE ? counts.blockFallback() : counts.fluidFallback();
    }

    public static RedirectCounts counts() {
        return lifetime().minus(baseline);
    }

    public static RedirectCounts lifetime() {
        return new RedirectCounts(SERVED[Api.BLOCK_STATE.ordinal()].sum(), FALLBACK[Api.BLOCK_STATE.ordinal()].sum(),
                SERVED[Api.FLUID_STATE.ordinal()].sum(), FALLBACK[Api.FLUID_STATE.ordinal()].sum());
    }

    public static long toggles() {
        return TOGGLES.sum();
    }

    public static void resetCounters() {
        baseline = lifetime();
    }

    public static int stateIdAt(boolean clientSide, String dimensionId, int x, int y, int z) {
        return stateIdAt(Api.BLOCK_STATE, clientSide, dimensionId, x, y, z);
    }

    public static int stateIdAt(Api api, boolean clientSide, String dimensionId, int x, int y, int z) {
        if (!enabled) return MISS;
        WDCChunk chunk = IngestionBridge.liveChunkForRead(clientSide, dimensionId, x >> 4, z >> 4);
        if (chunk == null) {
            FALLBACK[api.ordinal()].increment();
            return MISS;
        }
        if (!chunk.containsBlockY(y)) {
            FALLBACK[api.ordinal()].increment();
            return MISS;
        }
        int token = chunk.getToken(x, y, z);
        int id = BlockToken.isDefaultAir(token) ? IngestionBridge.defaultAirIdForRead(clientSide) : BlockToken.globalIdOf(token);
        if (id == MISS) {
            FALLBACK[api.ordinal()].increment();
            return MISS;
        }
        SERVED[api.ordinal()].increment();
        return id;
    }

    static void unresolved(Api api) {
        SERVED[api.ordinal()].decrement();
        FALLBACK[api.ordinal()].increment();
    }

    public static void resetForTests() {
        enabled = false;
        STARTUP_APPLIED.set(false);
        for (int i = 0; i < SERVED.length; i++) {
            SERVED[i].reset();
            FALLBACK[i].reset();
        }
        TOGGLES.reset();
        baseline = RedirectCounts.ZERO;
    }
}
