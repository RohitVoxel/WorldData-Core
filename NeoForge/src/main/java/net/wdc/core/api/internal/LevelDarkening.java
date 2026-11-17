package net.wdc.core.api.internal;

import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;

public final class LevelDarkening {

    private WeakReference<Level> level = new WeakReference<>(null);
    private boolean hasSky;
    private long dayTime = Long.MIN_VALUE;
    private float rain = Float.NaN;
    private float thunder = Float.NaN;
    private int value;

    public int of(Level current) {
        if (level.get() != current) {
            level = new WeakReference<>(current);
            hasSky = current.dimensionType().hasSkyLight();
            dayTime = Long.MIN_VALUE;
        }
        if (!hasSky) return 0;
        long time = current.getDayTime();
        float rainLevel = current.getRainLevel(1.0F);
        float thunderLevel = current.getThunderLevel(1.0F);
        if (time == dayTime && rainLevel == rain && thunderLevel == thunder) return value;
        value = SkyDarkening.of(current.dimensionType().timeOfDay(time), rainLevel, thunderLevel);
        dayTime = time;
        rain = rainLevel;
        thunder = thunderLevel;
        return value;
    }
}
