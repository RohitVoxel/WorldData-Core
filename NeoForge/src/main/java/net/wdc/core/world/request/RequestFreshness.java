package net.wdc.core.world.request;

public final class RequestFreshness {

    public enum Kind { CURRENT, ALLOW_STALE, MAX_AGE }

    private static final RequestFreshness CURRENT = new RequestFreshness(Kind.CURRENT, -1L);
    private static final RequestFreshness ALLOW_STALE = new RequestFreshness(Kind.ALLOW_STALE, -1L);

    private final Kind kind;
    private final long maxAgeTicks;

    private RequestFreshness(Kind kind, long maxAgeTicks) {
        this.kind = kind;
        this.maxAgeTicks = maxAgeTicks;
    }

    public static RequestFreshness current() { return CURRENT; }

    public static RequestFreshness allowStale() { return ALLOW_STALE; }

    public static RequestFreshness maxAge(long ticks) {
        if (ticks < 0) throw new IllegalArgumentException("ticks must be >= 0");
        return new RequestFreshness(Kind.MAX_AGE, ticks);
    }

    public Kind kind() { return kind; }

    public long maxAgeTicks() { return maxAgeTicks; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RequestFreshness other)) return false;
        return kind == other.kind && maxAgeTicks == other.maxAgeTicks;
    }

    @Override
    public int hashCode() { return kind.hashCode() * 31 + Long.hashCode(maxAgeTicks); }

    @Override
    public String toString() { return kind == Kind.MAX_AGE ? "MAX_AGE(" + maxAgeTicks + ")" : kind.name(); }
}
