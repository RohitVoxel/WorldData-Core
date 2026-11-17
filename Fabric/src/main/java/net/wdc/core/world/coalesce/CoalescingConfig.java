package net.wdc.core.world.coalesce;

public final class CoalescingConfig {

    public static final int DEFAULT_RESULT_BUFFER_CAPACITY = 256;

    public static final int DEFAULT_MAX_TRACKED_CANONICAL_WORK = 4096;

    private final int resultBufferCapacity;
    private final int maxTrackedCanonicalWork;

    public CoalescingConfig() {
        this(DEFAULT_RESULT_BUFFER_CAPACITY, DEFAULT_MAX_TRACKED_CANONICAL_WORK);
    }

    public CoalescingConfig(int resultBufferCapacity, int maxTrackedCanonicalWork) {
        if (resultBufferCapacity < 1) throw new IllegalArgumentException("resultBufferCapacity must be >= 1");
        if (maxTrackedCanonicalWork < 1) throw new IllegalArgumentException("maxTrackedCanonicalWork must be >= 1");
        this.resultBufferCapacity = resultBufferCapacity;
        this.maxTrackedCanonicalWork = maxTrackedCanonicalWork;
    }

    public int resultBufferCapacity() { return resultBufferCapacity; }
    public int maxTrackedCanonicalWork() { return maxTrackedCanonicalWork; }
}
