package net.wdc.core.scheduling;

import net.wdc.core.debug.WDCMetrics;

public final class WDCTickScheduler {

    public enum Phase { PIPELINE, OVERLAY, ENVIRONMENT, SLOW_SYSTEMS }

    private long tickCounter = 0L;

    private Runnable pipelineTask;
    private Runnable overlayTask;
    private Runnable environmentTask;
    private Runnable slowSystemsTask;

    public void register(Phase phase, Runnable task) {
        switch (phase) {
            case PIPELINE -> pipelineTask = task;
            case OVERLAY -> overlayTask = task;
            case ENVIRONMENT -> environmentTask = task;
            case SLOW_SYSTEMS -> slowSystemsTask = task;
        }
    }

    public void tick() {
        int phase = (int) (tickCounter % 4L);
        tickCounter++;

        long start = WDCMetrics.startNanos();
        switch (phase) {
            case 0 -> { if (pipelineTask != null) pipelineTask.run(); }
            case 1 -> { if (overlayTask != null) overlayTask.run(); }
            case 2 -> { if (environmentTask != null) environmentTask.run(); }
            case 3 -> { if (slowSystemsTask != null) slowSystemsTask.run(); }
            default -> { }
        }
        WDCMetrics.record(WDCMetrics.Timer.SCHEDULER_PROCESSING, System.nanoTime() - start);
    }

    public long currentTick() { return tickCounter; }

    public void reset() { tickCounter = 0L; }
}
