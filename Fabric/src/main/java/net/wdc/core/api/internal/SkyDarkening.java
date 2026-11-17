package net.wdc.core.api.internal;

public final class SkyDarkening {

    public static final int MAX_DARKENING = 11;

    private SkyDarkening() {}

    private record Last(float timeOfDay, float rain, float thunder, int darkening) {}

    private static volatile Last last = new Last(Float.NaN, 0.0F, 0.0F, 0);

    public static int of(float timeOfDay, float rainLevel, float thunderLevel) {
        Last cached = last;
        if (cached.timeOfDay == timeOfDay && cached.rain == rainLevel && cached.thunder == thunderLevel) {
            return cached.darkening;
        }
        int value = compute(timeOfDay, rainLevel, thunderLevel);
        last = new Last(timeOfDay, rainLevel, thunderLevel, value);
        return value;
    }

    private static int compute(float timeOfDay, float rainLevel, float thunderLevel) {
        double rain = 1.0 - (double) (clamp01(rainLevel) * 5.0F) / 16.0;
        double thunder = 1.0 - (double) (clamp01(thunderLevel) * 5.0F) / 16.0;
        double daylight = 0.5 + 2.0 * Math.max(-0.25, Math.min(0.25, Math.cos(timeOfDay * (Math.PI * 2.0))));
        int darkening = (int) ((1.0 - daylight * rain * thunder) * MAX_DARKENING);
        return Math.max(0, Math.min(MAX_DARKENING, darkening));
    }

    public static int effectiveSkyLight(int rawSkyLight, int darkening) {
        return Math.max(0, rawSkyLight - darkening);
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : Math.min(v, 1.0F);
    }
}
