package net.wdc.core.math;

public final class WDCMath {

    private WDCMath() {}

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static float smoothstep(float t) {
        return t * t * (3f - 2f * t);
    }

    public static float clamp(float v, float min, float max) {
        return Math.clamp(v, min, max);
    }

    public static float horizonFactor(float sunHeight, float sinAngle) {
        float horizonMask = clamp(1f - Math.abs(sunHeight) * 3f, 0f, 1f);
        float dawnFactor  = Math.max(0f,  sinAngle) * horizonMask;
        float duskFactor  = Math.max(0f, -sinAngle) * horizonMask;
        return Math.max(dawnFactor, duskFactor);
    }
}
