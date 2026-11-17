package net.wdc.core.world.cache;

public record DataResult<T>(DataFreshness status, T value, ChunkFingerprint fingerprint) {

    public static <T> DataResult<T> live(T value, ChunkFingerprint fingerprint) {
        if (value == null) throw new IllegalArgumentException("value must not be null for LIVE");
        if (fingerprint == null) throw new IllegalArgumentException("fingerprint must not be null for LIVE");
        return new DataResult<>(DataFreshness.LIVE, value, fingerprint);
    }

    public static <T> DataResult<T> stale(T value, ChunkFingerprint fingerprint) {
        if (value == null) throw new IllegalArgumentException("value must not be null for STALE");
        if (fingerprint == null) throw new IllegalArgumentException("fingerprint must not be null for STALE");
        return new DataResult<>(DataFreshness.STALE, value, fingerprint);
    }

    public static <T> DataResult<T> unavailable() {
        return new DataResult<>(DataFreshness.UNAVAILABLE, null, null);
    }

    public boolean isLive() {
        return status == DataFreshness.LIVE;
    }

    public boolean isStale() {
        return status == DataFreshness.STALE;
    }

    public boolean isUnavailable() {
        return status == DataFreshness.UNAVAILABLE;
    }

    public boolean hasValue() {
        return value != null;
    }
}
