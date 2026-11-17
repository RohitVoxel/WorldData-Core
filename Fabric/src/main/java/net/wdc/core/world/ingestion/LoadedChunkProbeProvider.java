package net.wdc.core.world.ingestion;

public final class LoadedChunkProbeProvider {

    private static volatile LoadedChunkProbe probe;

    private LoadedChunkProbeProvider() {}

    public static void set(LoadedChunkProbe next) {
        probe = next;
    }

    public static LoadedChunkProbe getIfPresent() {
        return probe;
    }

    public static void reset() {
        probe = null;
    }
}
