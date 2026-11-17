package net.wdc.core.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

import java.util.Optional;

public final class ServerTickInfo {

    private static final float NANOS_PER_MILLI = 1_000_000f;
    private static final float TARGET_MSPT = 50f;

    private ServerTickInfo() {}

    public static Optional<Snapshot> capture() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return Optional.empty();

        float mspt = server.getCurrentSmoothedTickTime();
        float tps = Math.min(20f, 1000f / Math.max(mspt, TARGET_MSPT));
        float budgetRemainingMs = TARGET_MSPT - mspt;
        return Optional.of(new Snapshot(tps, mspt, budgetRemainingMs));
    }

    public record Snapshot(float tps, float mspt, float budgetRemainingMs) {
    }
}
