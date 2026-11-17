package net.wdc.core.api.internal;

import java.util.function.Supplier;

public final class ClientApiRegistry {

    private static volatile Supplier<Object> clientApiSupplier;
    private static volatile WorldStateSource clientWorldStateSource;
    private static volatile LightSource clientLightSource;

    private ClientApiRegistry() {}

    public static void register(Supplier<Object> clientApi, WorldStateSource worldStateSource) {
        register(clientApi, worldStateSource, null);
    }

    public static void register(Supplier<Object> clientApi, WorldStateSource worldStateSource, LightSource lightSource) {
        clientApiSupplier = clientApi;
        clientWorldStateSource = worldStateSource;
        clientLightSource = lightSource;
    }

    public static Object getOrNull() {
        Supplier<Object> local = clientApiSupplier;
        return local == null ? null : local.get();
    }

    public static WorldStateSource currentWorldStateSource() {
        return clientWorldStateSource;
    }

    public static LightSource currentLightSource() {
        return clientLightSource;
    }

    public static void reset() {
        clientApiSupplier = null;
        clientWorldStateSource = null;
        clientLightSource = null;
    }
}
