package net.wdc.core.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Supplier;

public final class DebugLines {

    private static final Map<String, Supplier<List<String>>> PROVIDERS = new ConcurrentSkipListMap<>();

    private DebugLines() {}

    public static void register(String id, Supplier<List<String>> provider) {
        if (id == null || id.isBlank() || provider == null) throw new IllegalArgumentException("id/provider must not be null or blank");
        PROVIDERS.put(id, provider);
    }

    public static void unregister(String id) {
        if (id != null) PROVIDERS.remove(id);
    }

    public static List<String> collect() {
        List<String> lines = new ArrayList<>();
        for (Supplier<List<String>> provider : PROVIDERS.values()) {
            try {
                List<String> provided = provider.get();
                if (provided != null) lines.addAll(provided);
            } catch (RuntimeException ignored) {
            }
        }
        return lines;
    }

    public static void clear() { PROVIDERS.clear(); }
}
