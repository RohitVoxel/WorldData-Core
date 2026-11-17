package net.wdc.core.debug;

public final class PlayerCountSource {

    private static volatile int activePlayerCount = 0;

    private PlayerCountSource() {}

    public static void setActivePlayerCount(int count) { activePlayerCount = Math.max(0, count); }

    public static int getActivePlayerCount() { return activePlayerCount; }
}
