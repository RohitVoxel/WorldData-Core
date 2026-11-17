package net.wdc.core.config;

import java.nio.file.Path;

public final class CorePaths {

    public static final String DIRECTORY = "worlddata_core";
    public static final String CONFIG_FILE = "worlddata_core.json";
    public static final String REGION_DIRECTORY = "region";
    public static final String WORLD_META_FILE = "world.json";

    private static volatile Path configDirectory;
    private static volatile String worldKey = "";

    private CorePaths() {}

    public static void init(Path gameConfigDirectory) {
        if (gameConfigDirectory == null) throw new IllegalArgumentException("gameConfigDirectory must not be null");
        configDirectory = gameConfigDirectory;
    }

    public static boolean isInitialized() {
        return configDirectory != null;
    }

    public static Path configDirectory() {
        Path current = configDirectory;
        if (current == null) throw new IllegalStateException("CorePaths.init has not been called");
        return current;
    }

    public static Path root() {
        return configDirectory().resolve(DIRECTORY);
    }

    public static Path configFile() {
        return root().resolve(CONFIG_FILE);
    }

    public static void bindWorld(String key) {
        worldKey = key == null ? "" : key.trim();
    }

    public static void unbindWorld() {
        worldKey = "";
    }

    public static String worldKey() {
        return worldKey;
    }

    public static boolean hasWorld() {
        return !worldKey.isEmpty();
    }

    public static Path worldDirectory() {
        String key = worldKey;
        if (key.isEmpty()) throw new IllegalStateException("no world is bound");
        return root().resolve(safeName(key));
    }

    public static Path worldDirectory(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key must not be blank");
        return root().resolve(safeName(key));
    }

    public static Path dimensionDirectory(String dimensionId) {
        return worldDirectory().resolve(safeName(dimensionId));
    }

    public static Path dimensionDirectory(String key, String dimensionId) {
        return worldDirectory(key).resolve(safeName(dimensionId));
    }

    public static Path regionDirectory(String key, String dimensionId) {
        return dimensionDirectory(key, dimensionId).resolve(REGION_DIRECTORY);
    }

    public static String safeName(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        if (cleaned.equals(".") || cleaned.equals("..")) return "_" + cleaned;
        return cleaned;
    }
}
