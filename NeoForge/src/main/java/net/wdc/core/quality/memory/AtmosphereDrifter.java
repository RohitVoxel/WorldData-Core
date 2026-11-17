package net.wdc.core.quality.memory;

public final class AtmosphereDrifter {

    private final float accel;
    private final float damp;

    private float value;
    private float velocity;

    public AtmosphereDrifter(float initialValue, float accel, float damp) {
        this.value = initialValue;
        this.accel = accel;
        this.damp = damp;
    }

    public float advance(float target, float deltaSec) {
        if (deltaSec <= 0f) return value;

        float error = target - value;
        velocity += error * accel * deltaSec;
        velocity *= (float) Math.pow(damp, deltaSec * 20.0);
        value += velocity * deltaSec;

        return value;
    }

    public void snap(float newValue) {
        this.value = newValue;
        this.velocity = 0f;
    }

    public float value() {
        return value;
    }
}
