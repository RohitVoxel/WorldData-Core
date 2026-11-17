package net.wdc.core.world.coalesce;

import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.demand.WDCDemandService;
import net.wdc.core.world.request.RequestManager;
import net.wdc.core.world.scheduler.RequestScheduler;

public final class CoalescingCoordinatorProvider {

    private static volatile CoalescingCoordinator instance;

    private CoalescingCoordinatorProvider() {}

    public static CoalescingCoordinator getOrCreate(WDCDemandService demandService, WDCCache cache,
                                                      RequestManager requestManager, RequestScheduler scheduler) {
        CoalescingCoordinator local = instance;
        if (local == null) {
            synchronized (CoalescingCoordinatorProvider.class) {
                local = instance;
                if (local == null) {
                    local = new CoalescingCoordinator(demandService, cache, requestManager, scheduler);
                    instance = local;
                }
            }
        }
        return local;
    }

    public static CoalescingCoordinator get() {
        return instance;
    }

    public static CoalescingCoordinator getIfPresent() {
        return instance;
    }

    public static void reset() {
        instance = null;
    }
}
