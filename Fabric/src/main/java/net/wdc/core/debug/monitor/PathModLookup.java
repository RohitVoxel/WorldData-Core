package net.wdc.core.debug.monitor;

import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.function.Supplier;

public final class PathModLookup implements ModLookup {

    private final Supplier<Map<Path, String>> source;
    private volatile Map<Path, String> table;

    public PathModLookup(Supplier<Map<Path, String>> source) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        this.source = source;
    }

    public static Path normalize(Path path) {
        if (path == null) return null;
        try {
            return path.toAbsolutePath().normalize();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    public static Path pathOf(URL url) {
        if (url == null) return null;
        try {
            String text = url.toString();
            if (text.startsWith("jar:")) {
                text = text.substring(4);
                int bang = text.indexOf("!/");
                if (bang >= 0) text = text.substring(0, bang);
            }
            URI uri = URI.create(text);
            if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
            return normalize(Paths.get(uri));
        } catch (RuntimeException failure) {
            return null;
        }
    }

    public static Path locate(Class<?> type) {
        try {
            ProtectionDomain domain = type.getProtectionDomain();
            CodeSource codeSource = domain == null ? null : domain.getCodeSource();
            Path found = codeSource == null ? null : pathOf(codeSource.getLocation());
            if (found != null) return found;
            ClassLoader loader = type.getClassLoader();
            if (loader == null) return null;
            return pathOf(loader.getResource(type.getName().replace('.', '/') + ".class"));
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private Map<Path, String> table() {
        Map<Path, String> current = table;
        if (current != null) return current;
        synchronized (this) {
            current = table;
            if (current == null) {
                try {
                    current = Map.copyOf(source.get());
                } catch (RuntimeException failure) {
                    current = Map.of();
                }
                table = current;
            }
            return current;
        }
    }

    @Override
    public String modIdOf(Class<?> type) {
        Path location = locate(type);
        if (location == null) return null;
        Map<Path, String> known = table();
        String direct = known.get(location);
        if (direct != null) return direct;
        for (Map.Entry<Path, String> entry : known.entrySet()) {
            if (location.startsWith(entry.getKey())) return entry.getValue();
        }
        return null;
    }
}
