package net.wdc.core.api.extensions;

public final class ExtensionRegistryProvider {

    private static volatile ExtensionRegistry instance;

    private ExtensionRegistryProvider() {}

    public static ExtensionRegistry getOrCreate() {
        ExtensionRegistry local = instance;
        if (local == null) {
            synchronized (ExtensionRegistryProvider.class) {
                local = instance;
                if (local == null) {
                    local = new ExtensionRegistryImpl();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static ExtensionRegistry get() {
        return instance;
    }

    public static ExtensionRegistry getIfPresent() {
        return instance;
    }

    public static void reset() {
        instance = null;
    }
}
