package net.wdc.core.world.spill;

public final class SpillScopes {

    private SpillScopes() {}

    public static String singleplayer(String saveFolderName, long seed) {
        return "sp_" + clean(saveFolderName) + "_" + Long.toHexString(seed);
    }

    public static String dedicated(String levelName, long seed) {
        return "srv_" + clean(levelName) + "_" + Long.toHexString(seed);
    }

    public static String multiplayer(String address) {
        return "mp_" + clean(address);
    }

    private static String clean(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        return raw.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
