package net.wdc.core.api.changes;

import java.util.List;

public record WDCChangeBatch(
        String dimensionId,
        long sequence,
        long tick,
        List<WDCChunkChange> chunks,
        boolean truncated,
        boolean reset
) {
    public static final String ALL_DIMENSIONS = "*";

    public WDCChangeBatch {
        chunks = List.copyOf(chunks);
    }
}
