package net.wdc.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

public final class SessionMarker {

    public static final String FILE_NAME = "session.lock";

    private SessionMarker() {}

    public static boolean begin(Path directory) {
        return begin(directory, ProcessHandle.current().pid());
    }

    static boolean begin(Path directory, long pid) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(FILE_NAME);
            boolean crashed = false;
            if (Files.exists(file)) {
                OptionalLong previous = readPid(file);
                crashed = previous.isEmpty() || (previous.getAsLong() != pid && ProcessHandle.of(previous.getAsLong()).isEmpty());
            }
            Files.writeString(file, Long.toString(pid), StandardCharsets.UTF_8);
            return crashed;
        } catch (IOException | RuntimeException failed) {
            return false;
        }
    }

    public static void end(Path directory) {
        try {
            Files.deleteIfExists(directory.resolve(FILE_NAME));
        } catch (IOException ignored) {
        }
    }

    private static OptionalLong readPid(Path file) {
        try {
            return OptionalLong.of(Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim()));
        } catch (IOException | NumberFormatException unreadable) {
            return OptionalLong.empty();
        }
    }
}
