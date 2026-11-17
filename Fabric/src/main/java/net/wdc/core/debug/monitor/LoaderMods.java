package net.wdc.core.debug.monitor;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class LoaderMods {

    private LoaderMods() {}

    public static void install() {
        WDCApiMonitor.installModLookup(new PathModLookup(LoaderMods::collect));
    }

    static Map<Path, String> collect() {
        Map<Path, String> found = new HashMap<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            try {
                for (Path path : mod.getOrigin().getPaths()) {
                    Path normal = PathModLookup.normalize(path);
                    if (normal != null) found.putIfAbsent(normal, id);
                }
            } catch (RuntimeException unsupported) {
                continue;
            }
        }
        return found;
    }
}
