package net.wdc.core.world.spill;

import net.wdc.core.config.CorePaths;

public final class WDCSpillRoots {

    private static volatile boolean requireBinding = false;

    private WDCSpillRoots() {}

    public static void bind(String name) {
        CorePaths.bindWorld(name);
    }

    public static void unbind() {
        CorePaths.unbindWorld();
    }

    public static void setRequireBinding(boolean required) {
        requireBinding = required;
    }

    public static boolean isUsable() {
        return !requireBinding || CorePaths.hasWorld();
    }

    public static String worldFolder() { return CorePaths.worldKey(); }

    public static String scope(String dimensionId) {
        String current = CorePaths.worldKey();
        return current.isEmpty() ? dimensionId : current + "/" + dimensionId;
    }
}
