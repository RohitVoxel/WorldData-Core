package net.wdc.core.debug.diagnostics;

import net.wdc.core.api.request.WDCFreshness;

import java.util.List;

public record WDCChunkDump(
        String dimensionId,
        int chunkX,
        int chunkZ,
        WDCFreshness freshness,
        long version,
        long estimatedBytes,
        boolean biomePresent,
        String biomeId,
        List<WDCSectionDump> sections
) {
}
