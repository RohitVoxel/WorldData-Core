package net.wdc.core.api.world;

public record WDCWorldSnapshot(
        String dimensionId,
        long gameTime,
        long dayTime,
        boolean raining,
        boolean thundering,
        float rainLevel,
        float thunderLevel,
        int moonPhase,
        boolean clientWorld
) {

    public long timeOfDay() {
        return ((dayTime % 24000) + 24000) % 24000;
    }

    public boolean isDaytime() {
        long t = timeOfDay();
        return t < 12000;
    }
}
