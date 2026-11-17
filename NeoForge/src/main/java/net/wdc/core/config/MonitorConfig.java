package net.wdc.core.config;

public final class MonitorConfig {

    public static final int DEFAULT_SAMPLE_EVERY = 8;
    public static final int MIN_SAMPLE_EVERY = 1;
    public static final int MAX_SAMPLE_EVERY = 1024;
    public static final int DEFAULT_MAX_CALLERS = 256;
    public static final int MIN_MAX_CALLERS = 8;
    public static final int MAX_MAX_CALLERS = 2048;

    public boolean allowed = true;
    public int sampleEvery = DEFAULT_SAMPLE_EVERY;
    public int maxCallers = DEFAULT_MAX_CALLERS;

    public void validate() {
        if (sampleEvery < MIN_SAMPLE_EVERY || sampleEvery > MAX_SAMPLE_EVERY) {
            throw new IllegalArgumentException("sampleEvery must be within " + MIN_SAMPLE_EVERY + ".." + MAX_SAMPLE_EVERY);
        }
        if (maxCallers < MIN_MAX_CALLERS || maxCallers > MAX_MAX_CALLERS) {
            throw new IllegalArgumentException("maxCallers must be within " + MIN_MAX_CALLERS + ".." + MAX_MAX_CALLERS);
        }
    }

    public boolean clamp() {
        boolean changed = false;
        int every = Math.max(MIN_SAMPLE_EVERY, Math.min(MAX_SAMPLE_EVERY, sampleEvery));
        if (every != sampleEvery) { sampleEvery = every; changed = true; }
        int callers = Math.max(MIN_MAX_CALLERS, Math.min(MAX_MAX_CALLERS, maxCallers));
        if (callers != maxCallers) { maxCallers = callers; changed = true; }
        return changed;
    }

    public MonitorConfig copy() {
        MonitorConfig copy = new MonitorConfig();
        copy.allowed = allowed;
        copy.sampleEvery = sampleEvery;
        copy.maxCallers = maxCallers;
        return copy;
    }
}
