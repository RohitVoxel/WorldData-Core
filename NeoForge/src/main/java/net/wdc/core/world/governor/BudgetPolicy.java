package net.wdc.core.world.governor;

import net.wdc.core.config.BudgetConfig;

public record BudgetPolicy(int sharePercent, long fixedNanos, long absoluteMaxNanos) {
    private static final long NANOS_PER_MS = 1_000_000L;
    public static final BudgetPolicy DEFAULT = from(new BudgetConfig());

    public static BudgetPolicy from(BudgetConfig config) {
        return new BudgetPolicy(config.ingestShareOfRemainingTickPercent, config.fixedMs * NANOS_PER_MS,
                config.absoluteMaxMs * NANOS_PER_MS);
    }

    public BudgetPolicy withSharePercent(int percent) {
        return new BudgetPolicy(percent, fixedNanos, absoluteMaxNanos);
    }
}
