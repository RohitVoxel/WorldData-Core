package net.wdc.core.redirect;

public record RedirectCounts(long blockServed, long blockFallback, long fluidServed, long fluidFallback) {

    public static final RedirectCounts ZERO = new RedirectCounts(0L, 0L, 0L, 0L);

    public long served() {
        return blockServed + fluidServed;
    }

    public long fallback() {
        return blockFallback + fluidFallback;
    }

    public RedirectCounts minus(RedirectCounts base) {
        return new RedirectCounts(
                Math.max(0L, blockServed - base.blockServed),
                Math.max(0L, blockFallback - base.blockFallback),
                Math.max(0L, fluidServed - base.fluidServed),
                Math.max(0L, fluidFallback - base.fluidFallback));
    }
}
