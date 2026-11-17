package net.wdc.core.debug;

import net.wdc.core.config.WDCConfig;

public final class OpsPanel {
    private static volatile boolean externalVisible = true;
    private static volatile boolean internalVisible = true;

    private OpsPanel() {}

    public static boolean isExternalVisible() {
        return externalVisible;
    }

    public static boolean isInternalVisible() {
        return internalVisible;
    }

    public static void setExternalVisible(boolean value) {
        externalVisible = value;
    }

    public static void setInternalVisible(boolean value) {
        internalVisible = value;
    }

    public static boolean toggleExternal() {
        boolean next = !externalVisible;
        externalVisible = next;
        WDCConfig.get().debug.showOpsExternal = next;
        WDCConfig.save();
        return next;
    }

    public static boolean toggleInternal() {
        boolean next = !internalVisible;
        internalVisible = next;
        WDCConfig.get().debug.showOpsInternal = next;
        WDCConfig.save();
        return next;
    }

    public static void loadFrom(WDCConfig config) {
        externalVisible = config.debug.showOpsExternal;
        internalVisible = config.debug.showOpsInternal;
    }
}
