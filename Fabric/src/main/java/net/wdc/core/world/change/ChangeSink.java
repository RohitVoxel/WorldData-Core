package net.wdc.core.world.change;

import net.wdc.core.world.cache.DataFreshness;

public interface ChangeSink {

    ChangeSink NONE = new ChangeSink() {
        @Override public boolean wantsBlockDetails() { return false; }
        @Override public void blockChanged(String dimensionId, int x, int y, int z, int oldStateId, int newStateId,
                                           String oldBlockId, String newBlockId) {}
        @Override public void chunkReread(String dimensionId, int chunkX, int chunkZ, int[] changedSectionYs, int count) {}
    };

    boolean wantsBlockDetails();

    void blockChanged(String dimensionId, int x, int y, int z, int oldStateId, int newStateId,
                      String oldBlockId, String newBlockId);

    void chunkReread(String dimensionId, int chunkX, int chunkZ, int[] changedSectionYs, int count);
}
