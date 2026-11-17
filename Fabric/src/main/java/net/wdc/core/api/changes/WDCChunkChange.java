package net.wdc.core.api.changes;

import java.util.List;
import java.util.Set;

public record WDCChunkChange(
        int chunkX,
        int chunkZ,
        Set<WDCChangeKind> kinds,
        List<Integer> changedSectionYs,
        List<WDCBlockChange> blockChanges,
        boolean blockDetailsComplete,
        boolean derivedRebuilt
) {
    public WDCChunkChange {
        kinds = Set.copyOf(kinds);
        changedSectionYs = List.copyOf(changedSectionYs);
        blockChanges = List.copyOf(blockChanges);
    }

    public boolean has(WDCChangeKind kind) {
        return kinds.contains(kind);
    }
}
