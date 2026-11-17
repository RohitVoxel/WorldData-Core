package net.wdc.core.scheduling;

public final class UpdateInterval {

    private final int intervalTicks;
    private long lastRunTick = Long.MIN_VALUE;

    public UpdateInterval(int intervalTicks) {
        if (intervalTicks < 1) {
            throw new IllegalArgumentException("intervalTicks must be >= 1, got " + intervalTicks);
        }
        this.intervalTicks = intervalTicks;
    }

    public boolean shouldRun(long currentTick) {
        if (lastRunTick == Long.MIN_VALUE || currentTick - lastRunTick >= intervalTicks) {
            lastRunTick = currentTick;
            return true;
        }
        return false;
    }

    public void reset() {
        lastRunTick = Long.MIN_VALUE;
    }
}
