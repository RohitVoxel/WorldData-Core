package net.wdc.core.scheduling;

public final class VersionGate {

    private long lastSeenVersion = -1L;

    public boolean changed(long currentVersion) {
        if (currentVersion != lastSeenVersion) {
            lastSeenVersion = currentVersion;
            return true;
        }
        return false;
    }

    public void reset() {
        lastSeenVersion = -1L;
    }
}
