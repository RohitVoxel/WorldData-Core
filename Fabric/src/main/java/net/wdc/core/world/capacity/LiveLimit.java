package net.wdc.core.world.capacity;

import net.wdc.core.world.governor.IngestionPerformanceConfig;

public final class LiveLimit {

    public enum Decision { ADMIT, WAIT, SKIP }

    public record Usage(int total, int limit) {
        public boolean full() { return total >= limit; }
    }

    private LiveLimit() {}

    public static Decision decide(int liveTotal, boolean needed, IngestionPerformanceConfig cfg) {
        if (liveTotal < cfg.liveLimit) return Decision.ADMIT;
        return needed ? Decision.WAIT : Decision.SKIP;
    }

    public static Usage usage(int liveTotal, IngestionPerformanceConfig cfg) {
        return new Usage(liveTotal, cfg.liveLimit);
    }
}
