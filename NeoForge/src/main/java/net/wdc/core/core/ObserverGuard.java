package net.wdc.core.core;

import org.slf4j.Logger;

public final class ObserverGuard {

    private final String side;
    private final int threshold;
    private final Logger logger;
    private volatile boolean disabled;
    private int consecutive;

    public ObserverGuard(String side, int threshold, Logger logger) {
        if (threshold < 1) throw new IllegalArgumentException("threshold must be >= 1");
        this.side = side;
        this.threshold = threshold;
        this.logger = logger;
    }

    public boolean isDisabled() { return disabled; }

    public synchronized void success() { consecutive = 0; }

    public synchronized void failure(RuntimeException cause) {
        consecutive++;
        if (disabled) return;
        if (consecutive >= threshold) {
            disabled = true;
            logger.error("WorldData Core {} chunk observer disabled after {} consecutive errors; it re-arms on the next world join",
                    side, consecutive, cause);
        } else {
            logger.warn("WorldData Core {} chunk observer error ({} of {}): {}", side, consecutive, threshold, cause.toString());
        }
    }

    public synchronized void reset() {
        disabled = false;
        consecutive = 0;
    }
}
