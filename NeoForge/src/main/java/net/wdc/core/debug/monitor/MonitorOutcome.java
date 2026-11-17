package net.wdc.core.debug.monitor;

public enum MonitorOutcome {
    LIVE,
    STALE,
    UNLOADED,
    PENDING,
    UNAVAILABLE,
    UNKNOWN;

    public static final int COUNT = values().length;

    private static final MonitorOutcome[] ALL = values();

    public boolean known() {
        return this != UNKNOWN;
    }

    public boolean served() {
        return this == LIVE || this == STALE;
    }

    public static MonitorOutcome of(int ordinal) {
        return ALL[ordinal];
    }
}
