package net.wdc.core.world.governor;

final class MovementSpeedTracker {
    private static final double SMOOTHING = 0.25;

    private Double lastX, lastY, lastZ;
    private long lastSampleNanos;

    private double smoothedBlocksPerSecond = 0.0;

    void sample(double x, double y, double z, long nowNanos) {
        if (lastX != null) {
            double dt = (nowNanos - lastSampleNanos) / 1_000_000_000.0;
            if (dt > 0.001) {
                double dx = x - lastX, dy = y - lastY, dz = z - lastZ;
                double instantSpeed = Math.sqrt(dx * dx + dy * dy + dz * dz) / dt;
                smoothedBlocksPerSecond += SMOOTHING * (instantSpeed - smoothedBlocksPerSecond);
            }
        }
        lastX = x; lastY = y; lastZ = z;
        lastSampleNanos = nowNanos;
    }

    double blocksPerSecond() {
        return smoothedBlocksPerSecond;
    }

    void reset() {
        lastX = null; lastY = null; lastZ = null;
        smoothedBlocksPerSecond = 0.0;
    }
}
