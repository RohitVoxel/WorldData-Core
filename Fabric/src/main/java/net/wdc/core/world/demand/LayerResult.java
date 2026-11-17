package net.wdc.core.world.demand;

public record LayerResult<T>(DemandFreshness freshness, T value) {
    public LayerResult {
        if (freshness == null) throw new IllegalArgumentException("freshness must not be null");
        if ((freshness == DemandFreshness.LIVE || freshness == DemandFreshness.STALE) && value == null) {
            throw new IllegalArgumentException("LIVE/STALE layers must carry a non-null value");
        }
    }

    public boolean isLive() { return freshness == DemandFreshness.LIVE; }
    public boolean isStale() { return freshness == DemandFreshness.STALE; }
    public boolean isUnavailable() { return freshness == DemandFreshness.UNAVAILABLE; }
    public boolean isAcquiring() { return freshness == DemandFreshness.ACQUIRING; }
}
