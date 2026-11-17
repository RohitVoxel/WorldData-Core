package net.wdc.core.world.scheduler;

import net.wdc.core.world.request.RequestManager;

public final class RequestSchedulerProvider {

    private static volatile RequestScheduler instance;

    private RequestSchedulerProvider() {}

    public static RequestScheduler getOrCreate(RequestManager requestManager) {
        RequestScheduler local = instance;
        if (local == null) {
            synchronized (RequestSchedulerProvider.class) {
                local = instance;
                if (local == null) {
                    local = new RequestScheduler(requestManager, new SchedulerConfig());
                    instance = local;
                }
            }
        }
        return local;
    }

    public static RequestScheduler get() {
        return instance;
    }

    public static RequestScheduler getIfPresent() {
        return instance;
    }

    public static void reset() {
        instance = null;
    }
}
