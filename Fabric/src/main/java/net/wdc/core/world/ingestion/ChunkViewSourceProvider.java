package net.wdc.core.world.ingestion;

public final class ChunkViewSourceProvider {

    private static volatile ChunkViewSource source;

    private ChunkViewSourceProvider() {}

    public static void set(ChunkViewSource next) {
        source = next;
    }

    public static ChunkViewSource getIfPresent() {
        return source;
    }

    public static void reset() {
        source = null;
    }
}
