package net.wdc.core.world.governor;

public final class IngestionPerformanceConfig {

    public static final int MAX_QUEUE_CAPACITY = 65_536;
    public static final int LEGACY_QUEUE_CAPACITY = 512;
    public static final int PREVIOUS_DEFAULT_QUEUE_CAPACITY = 4096;
    public static final int DEFAULT_QUEUE_CAPACITY = 12_288;
    public static final int DEFAULT_LIVE_LIMIT = 4096;
    public static final int MAX_LIVE_LIMIT = 32_768;
    public static final int DEFAULT_LIVE_RELEASE_GRACE_SECONDS = 30;
    public static final int MAX_LIVE_RELEASE_GRACE_SECONDS = 3600;
    public static final int PREVIOUS_DEFAULT_TIMEOUT_TICKS = 100;
    public static final int DEFAULT_TIMEOUT_TICKS = 40;
    public static final int CURRENT_SCHEMA = 3;
    public static final int LEGACY_DEFAULT_BUDGET_PERCENT = 50;
    public static final int MAX_TIMEOUT_TICKS = 100;

    private String profile;

    public int schema;

    public Integer remainingBudgetPercent;
    public int queueCapacity = DEFAULT_QUEUE_CAPACITY;
    public transient int liveLimit = DEFAULT_LIVE_LIMIT;
    public int liveReleaseGraceSeconds = DEFAULT_LIVE_RELEASE_GRACE_SECONDS;
    public int deferredMax = 0;
    public int timeoutTicks = DEFAULT_TIMEOUT_TICKS;

    public long liveReleaseGraceNanos() { return liveReleaseGraceSeconds * 1_000_000_000L; }

    public void validate() {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be >= 1");
        if (queueCapacity > MAX_QUEUE_CAPACITY) throw new IllegalArgumentException("queueCapacity must be <= " + MAX_QUEUE_CAPACITY);
        if (liveLimit < 1 || liveLimit > MAX_LIVE_LIMIT) throw new IllegalArgumentException("liveLimit must be within 1.." + MAX_LIVE_LIMIT);
        if (liveReleaseGraceSeconds < 0 || liveReleaseGraceSeconds > MAX_LIVE_RELEASE_GRACE_SECONDS) {
            throw new IllegalArgumentException("liveReleaseGraceSeconds must be within 0.." + MAX_LIVE_RELEASE_GRACE_SECONDS);
        }
        if (deferredMax < 0 || deferredMax > MAX_QUEUE_CAPACITY) throw new IllegalArgumentException("deferredMax must be within 0.." + MAX_QUEUE_CAPACITY);
        if (timeoutTicks < 1 || timeoutTicks > MAX_TIMEOUT_TICKS) throw new IllegalArgumentException("timeoutTicks must be within 1.." + MAX_TIMEOUT_TICKS);
    }

    public boolean clampToConfigCeilings() {
        boolean changed = false;
        if (queueCapacity > MAX_QUEUE_CAPACITY) { queueCapacity = MAX_QUEUE_CAPACITY; changed = true; }
        if (liveLimit > MAX_LIVE_LIMIT) { liveLimit = MAX_LIVE_LIMIT; changed = true; }
        if (liveLimit < 1) { liveLimit = DEFAULT_LIVE_LIMIT; changed = true; }
        return changed;
    }

    public boolean migrateLegacy() {
        boolean changed = false;
        if (schema < CURRENT_SCHEMA) {
            if (queueCapacity == PREVIOUS_DEFAULT_QUEUE_CAPACITY) queueCapacity = DEFAULT_QUEUE_CAPACITY;
            if (timeoutTicks == PREVIOUS_DEFAULT_TIMEOUT_TICKS) timeoutTicks = DEFAULT_TIMEOUT_TICKS;
            schema = CURRENT_SCHEMA;
            changed = true;
        }
        String legacy = profile;
        boolean custom = legacy != null && legacy.equalsIgnoreCase("CUSTOM");
        if (queueCapacity == LEGACY_QUEUE_CAPACITY && !custom) {
            queueCapacity = DEFAULT_QUEUE_CAPACITY;
            changed = true;
        }
        if (legacy != null) {
            remainingBudgetPercent = percentForLegacyProfile(legacy);
            profile = null;
            changed = true;
        }
        return changed;
    }

    static int percentForLegacyProfile(String legacyProfile) {
        if (legacyProfile == null) return LEGACY_DEFAULT_BUDGET_PERCENT;
        return switch (legacyProfile.toUpperCase(java.util.Locale.ROOT)) {
            case "CONSERVATIVE" -> 25;
            case "AGGRESSIVE" -> 70;
            default -> LEGACY_DEFAULT_BUDGET_PERCENT;
        };
    }

    public Integer takeLegacyBudgetShare() {
        Integer legacy = remainingBudgetPercent;
        remainingBudgetPercent = null;
        if (legacy == null || legacy == LEGACY_DEFAULT_BUDGET_PERCENT) return null;
        return legacy;
    }

    public IngestionPerformanceConfig copy() {
        IngestionPerformanceConfig copy = new IngestionPerformanceConfig();
        copy.schema = schema;
        copy.queueCapacity = queueCapacity;
        copy.liveLimit = liveLimit;
        copy.liveReleaseGraceSeconds = liveReleaseGraceSeconds;
        copy.deferredMax = deferredMax;
        copy.timeoutTicks = timeoutTicks;
        return copy;
    }
}
