package net.wdc.core.world.change;

public final class WorldChangeTrackerProvider {

    private static final WorldChangeTracker TRACKER = new WorldChangeTracker();
    private static final DirtyChunkRescanner RESCANNER = new DirtyChunkRescanner();

    private WorldChangeTrackerProvider() {}

    public static WorldChangeTracker getOrCreate() {
        return TRACKER;
    }

    public static DirtyChunkRescanner rescanner() {
        return RESCANNER;
    }
}
