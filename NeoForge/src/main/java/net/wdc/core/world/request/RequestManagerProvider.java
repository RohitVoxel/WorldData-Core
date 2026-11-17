package net.wdc.core.world.request;

public final class RequestManagerProvider {

    private static volatile RequestManager instance;

    private RequestManagerProvider() {}

    public static RequestManager getOrCreate() {
        RequestManager local = instance;
        if (local == null) {
            synchronized (RequestManagerProvider.class) {
                local = instance;
                if (local == null) {
                    local = new RequestManager();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static RequestManager get() {
        return instance;
    }

    public static RequestManager getIfPresent() {
        return instance;
    }

    public static void reset() {
        instance = null;
    }
}
