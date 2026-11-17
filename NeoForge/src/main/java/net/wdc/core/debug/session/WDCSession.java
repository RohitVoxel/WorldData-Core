package net.wdc.core.debug.session;

import net.wdc.core.config.CorePaths;
import net.wdc.core.config.WDCConfig;

import java.time.ZonedDateTime;

public final class WDCSession {

    public static final String DIRECTORY = "sessions";

    private static final SessionMonitor SHARED = new SessionMonitor(System::nanoTime, ZonedDateTime::now,
            () -> CorePaths.root().resolve(DIRECTORY), () -> WDCConfig.get().session.allowed);

    private WDCSession() {}

    public static SessionMonitor monitor() {
        return SHARED;
    }

    public static void onTickStart() {
        SHARED.onTickStart();
    }

    public static void onTickEnd() {
        SHARED.onTickEnd();
    }

    public static void onServerStopping() {
        SHARED.onServerStopping();
    }
}
