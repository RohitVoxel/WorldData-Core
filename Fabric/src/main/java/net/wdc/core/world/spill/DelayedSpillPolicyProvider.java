package net.wdc.core.world.spill;

import java.util.function.Supplier;

public final class DelayedSpillPolicyProvider {
    private static volatile DelayedSpillPolicy instance;

    private DelayedSpillPolicyProvider() {}

    public static DelayedSpillPolicy getOrCreate(Supplier<DelayedSpillPolicy> factory) {
        DelayedSpillPolicy local = instance;
        if (local == null) {
            synchronized (DelayedSpillPolicyProvider.class) {
                local = instance;
                if (local == null) {
                    local = factory.get();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static DelayedSpillPolicy get() {
        return instance;
    }

    public static void reset() {
        DelayedSpillPolicy local = instance;
        instance = null;
        if (local != null) {
            local.shutdown();
            local.reset();
        }
    }
}
