package net.wdc.core.config;

public final class BudgetConfig {
    public static final int ABSOLUTE_MAX_MS = 16;
    public static final int DEFAULT_SHARE_PERCENT = 10;
    public static final int DEFAULT_FIXED_MS = 4;
    public static final int MIN_SHARE_PERCENT = 1;
    public static final int MAX_SHARE_PERCENT = 80;

    public int absoluteMaxMs = ABSOLUTE_MAX_MS;
    public int ingestShareOfRemainingTickPercent = DEFAULT_SHARE_PERCENT;
    public int fixedMs = DEFAULT_FIXED_MS;

    public void validate() {
        if (absoluteMaxMs < 1 || absoluteMaxMs > ABSOLUTE_MAX_MS) {
            throw new IllegalArgumentException("absoluteMaxMs must be within 1.." + ABSOLUTE_MAX_MS);
        }
        if (ingestShareOfRemainingTickPercent < MIN_SHARE_PERCENT || ingestShareOfRemainingTickPercent > MAX_SHARE_PERCENT) {
            throw new IllegalArgumentException("ingestShareOfRemainingTickPercent must be within "
                    + MIN_SHARE_PERCENT + ".." + MAX_SHARE_PERCENT);
        }
        if (fixedMs < 1 || fixedMs > absoluteMaxMs) throw new IllegalArgumentException("fixedMs must be within 1.." + absoluteMaxMs);
    }

    public boolean clamp() {
        boolean changed = false;
        int abs = Math.max(1, Math.min(ABSOLUTE_MAX_MS, absoluteMaxMs));
        if (abs != absoluteMaxMs) { absoluteMaxMs = abs; changed = true; }
        int share = Math.max(MIN_SHARE_PERCENT, Math.min(MAX_SHARE_PERCENT, ingestShareOfRemainingTickPercent));
        if (share != ingestShareOfRemainingTickPercent) { ingestShareOfRemainingTickPercent = share; changed = true; }
        int fixed = Math.max(1, Math.min(absoluteMaxMs, fixedMs));
        if (fixed != fixedMs) { fixedMs = fixed; changed = true; }
        return changed;
    }

    public BudgetConfig copy() {
        BudgetConfig copy = new BudgetConfig();
        copy.absoluteMaxMs = absoluteMaxMs;
        copy.ingestShareOfRemainingTickPercent = ingestShareOfRemainingTickPercent;
        copy.fixedMs = fixedMs;
        return copy;
    }
}
