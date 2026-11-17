package net.wdc.core.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public final class WorldMeta {

    public record Info(String key, String kind, String levelName, long seed, long lastOpenedEpochMillis) {}

    private WorldMeta() {}

    public static boolean touch(String key, String kind, String levelName, long seed, long nowEpochMillis) {
        if (key == null || key.isBlank() || !CorePaths.isInitialized()) return false;
        Path dir = CorePaths.worldDirectory(key);
        Path file = dir.resolve(CorePaths.WORLD_META_FILE);
        JsonObject object = new JsonObject();
        object.addProperty("key", key);
        object.addProperty("kind", kind == null ? "" : kind);
        object.addProperty("levelName", levelName == null ? "" : levelName);
        object.addProperty("seed", seed);
        object.addProperty("lastOpenedEpochMillis", nowEpochMillis);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(dir);
            Files.writeString(tmp, object.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException notSupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException failed) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    public static Optional<Info> read(String key) {
        if (key == null || key.isBlank() || !CorePaths.isInitialized()) return Optional.empty();
        Path file = CorePaths.worldDirectory(key).resolve(CorePaths.WORLD_META_FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return Optional.of(new Info(
                    object.get("key").getAsString(),
                    object.get("kind").getAsString(),
                    object.get("levelName").getAsString(),
                    object.get("seed").getAsLong(),
                    object.get("lastOpenedEpochMillis").getAsLong()));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}
