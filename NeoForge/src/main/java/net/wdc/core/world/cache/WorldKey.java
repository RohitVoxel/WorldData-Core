package net.wdc.core.world.cache;

public final class WorldKey {

    private final String dimensionId;
    private final long sessionId;

    WorldKey(String dimensionId, long sessionId) {
        if (dimensionId == null || dimensionId.isEmpty()) {
            throw new IllegalArgumentException("dimensionId must not be null or empty");
        }
        this.dimensionId = dimensionId;
        this.sessionId = sessionId;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public long sessionId() {
        return sessionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorldKey other)) return false;
        return sessionId == other.sessionId && dimensionId.equals(other.dimensionId);
    }

    @Override
    public int hashCode() {
        return dimensionId.hashCode() * 31 + Long.hashCode(sessionId);
    }

    @Override
    public String toString() {
        return dimensionId + "#" + sessionId;
    }
}
