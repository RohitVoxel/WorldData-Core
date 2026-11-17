package net.wdc.core.world.storage;

public final class HeatScan {

    private HeatScan() {}

    public record Rules(int readsThreshold, int maxMutations, int promoteWindows, int demoteWindows) {}

    public interface Sink {
        void candidate(WDCChunk chunk, int sectionY, int reads);

        void idle(WDCChunk chunk, int sectionY);
    }
}
