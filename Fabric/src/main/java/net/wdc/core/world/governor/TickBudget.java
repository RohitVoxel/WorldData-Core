package net.wdc.core.world.governor;

public final class TickBudget {
    public static final int MIN_BUDGET_PERCENT = 1;
    public static final int MAX_BUDGET_PERCENT = 80;
    public static final int DEFAULT_BUDGET_PERCENT = 10;
    public static final long DEFAULT_TARGET_TICK_NANOS = 50_000_000L;
    public static final long LATE_TICK_REMAINING_NANOS = 1_000_000L;

    private volatile BudgetPolicy policy = BudgetPolicy.DEFAULT;
    private volatile Snapshot last = Snapshot.NONE;
    private Thread tickThread;
    private long targetTickNanos = DEFAULT_TARGET_TICK_NANOS;
    private long tickStartNanos;
    private boolean tickActive;
    private boolean sliceOpen;
    private long remainingNanos;
    private long sliceNanos;
    private long usedNanos;
    private boolean lateSlice;

    public record Snapshot(int percent, long remainingNanos, long sliceNanos, long usedNanos, boolean measured,
                           long fixedNanos, long absoluteMaxNanos, boolean late) {
        public static final Snapshot NONE = new Snapshot(DEFAULT_BUDGET_PERCENT, 0L, 0L, 0L, false,
                BudgetPolicy.DEFAULT.fixedNanos(), BudgetPolicy.DEFAULT.absoluteMaxNanos(), false);

        public long maxAllowedNanos() {
            return absoluteMaxNanos;
        }

        public long softRemainingNanos() {
            return Math.max(0L, sliceNanos - usedNanos);
        }
    }

    public static boolean isValidPercent(int value) {
        return value >= MIN_BUDGET_PERCENT && value <= MAX_BUDGET_PERCENT;
    }

    public int percent() { return policy.sharePercent(); }

    public void setPercent(int value) {
        if (!isValidPercent(value)) {
            throw new IllegalArgumentException("budget percent must be within " + MIN_BUDGET_PERCENT + ".." + MAX_BUDGET_PERCENT);
        }
        this.policy = policy.withSharePercent(value);
    }

    public BudgetPolicy policy() { return policy; }

    public void setPolicy(BudgetPolicy next) {
        if (next == null) throw new IllegalArgumentException("policy must not be null");
        if (!isValidPercent(next.sharePercent())) {
            throw new IllegalArgumentException("budget percent must be within " + MIN_BUDGET_PERCENT + ".." + MAX_BUDGET_PERCENT);
        }
        this.policy = next;
    }

    public void beginTick(long nowNanos, long targetNanos) {
        this.tickThread = Thread.currentThread();
        this.targetTickNanos = targetNanos > 0 ? targetNanos : DEFAULT_TARGET_TICK_NANOS;
        this.tickStartNanos = nowNanos;
        this.tickActive = true;
        this.sliceOpen = false;
        this.remainingNanos = 0L;
        this.sliceNanos = 0L;
        this.usedNanos = 0L;
        this.lateSlice = false;
    }

    public boolean isTickActive() { return tickActive; }
    public long targetTickNanos() { return targetTickNanos; }
    public long tickElapsedNanos(long nowNanos) { return tickActive ? Math.max(0L, nowNanos - tickStartNanos) : 0L; }
    public boolean isDrainWindowOpen() { return tickActive && sliceOpen; }
    public boolean isTickThread() { return tickActive && Thread.currentThread() == tickThread; }

    public void openDrainWindow(long nowNanos) {
        if (!tickActive || sliceOpen) return;
        BudgetPolicy current = policy;
        long remaining = targetTickNanos - (nowNanos - tickStartNanos);
        remainingNanos = Math.max(0L, remaining);
        lateSlice = remainingNanos < LATE_TICK_REMAINING_NANOS;
        sliceNanos = baseSlice(current, remainingNanos);
        usedNanos = 0L;
        sliceOpen = true;
        publish();
    }

    public long leftoverNanos(long nowNanos) {
        if (!tickActive || !sliceOpen) return 0L;
        long sliceLeft = sliceNanos - usedNanos;
        if (sliceLeft <= 0L) return 0L;
        if (lateSlice) return sliceLeft;
        long realRemaining = targetTickNanos - (nowNanos - tickStartNanos);
        if (realRemaining <= 0L) return 0L;
        return Math.min(sliceLeft, share(realRemaining, policy.sharePercent()));
    }

    public void charge(long nanos) {
        if (!tickActive || !sliceOpen || nanos <= 0L) return;
        usedNanos += nanos;
        publish();
    }

    public Snapshot snapshot() {
        Snapshot current = last;
        BudgetPolicy now = policy;
        if (current.measured()) return current;
        return new Snapshot(now.sharePercent(), current.remainingNanos(), current.sliceNanos(), current.usedNanos(), false,
                now.fixedNanos(), now.absoluteMaxNanos(), false);
    }

    public void reset() {
        tickActive = false;
        sliceOpen = false;
        tickThread = null;
        remainingNanos = 0L;
        sliceNanos = 0L;
        usedNanos = 0L;
        lateSlice = false;
        last = Snapshot.NONE;
    }

    static long baseSlice(BudgetPolicy policy, long remainingNanos) {
        long base = remainingNanos < LATE_TICK_REMAINING_NANOS
                ? policy.fixedNanos()
                : share(remainingNanos, policy.sharePercent());
        return Math.min(base, policy.absoluteMaxNanos());
    }

    private void publish() {
        BudgetPolicy current = policy;
        last = new Snapshot(current.sharePercent(), remainingNanos, sliceNanos, usedNanos, true,
                current.fixedNanos(), current.absoluteMaxNanos(), lateSlice);
    }

    static long share(long nanos, int percent) {
        return nanos / 100L * percent + (nanos % 100L) * percent / 100L;
    }
}
