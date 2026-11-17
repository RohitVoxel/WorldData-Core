package net.wdc.core.world.scheduler;

public final class SchedulerConfig {

    public int maxActiveWork = 64;

    public int maxReadySetSize = 128;

    public int defaultLookahead = 4;

    public int maxLookahead = 1_000_000;

    public int defaultNeighborhoodRadius = 1;

    public int maxNeighborhoodRadius = 2;

    public int defaultBatchWidth = 8;

    public long agingThresholdTicks = 100L;

    public void validate() {
        if (maxActiveWork < 1) throw new IllegalArgumentException("maxActiveWork must be >= 1");
        if (maxReadySetSize < 1) throw new IllegalArgumentException("maxReadySetSize must be >= 1");
        if (defaultLookahead < 1) throw new IllegalArgumentException("defaultLookahead must be >= 1");
        if (maxLookahead < defaultLookahead) throw new IllegalArgumentException("maxLookahead must be >= defaultLookahead");
        if (defaultNeighborhoodRadius < 0) throw new IllegalArgumentException("defaultNeighborhoodRadius must be >= 0");
        if (maxNeighborhoodRadius < defaultNeighborhoodRadius) throw new IllegalArgumentException("maxNeighborhoodRadius must be >= defaultNeighborhoodRadius");
        if (defaultBatchWidth < 1) throw new IllegalArgumentException("defaultBatchWidth must be >= 1");
        if (agingThresholdTicks < 1) throw new IllegalArgumentException("agingThresholdTicks must be >= 1");
    }
}
