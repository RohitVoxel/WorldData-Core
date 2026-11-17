package net.wdc.core.world.change;

import net.wdc.core.api.changes.WDCChangeEntrypoint;
import net.wdc.core.api.changes.WDCChangeFilter;
import net.wdc.core.api.changes.WDCSubscription;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ChangeEntrypoints {

    private ChangeEntrypoints() {}

    public static List<WDCSubscription> registerAll(WorldChangeTracker tracker, Supplier<List<WDCChangeEntrypoint>> source,
                                                      Consumer<Throwable> onFailure) {
        List<WDCSubscription> subscriptions = new ArrayList<>();
        List<WDCChangeEntrypoint> entrypoints;
        try {
            entrypoints = source.get();
        } catch (RuntimeException failure) {
            onFailure.accept(failure);
            return subscriptions;
        }
        for (WDCChangeEntrypoint entrypoint : entrypoints) {
            try {
                WDCChangeFilter filter = entrypoint.filter();
                subscriptions.add(tracker.subscribe(filter == null ? WDCChangeFilter.all() : filter, entrypoint));
            } catch (RuntimeException failure) {
                onFailure.accept(failure);
            }
        }
        return subscriptions;
    }
}
