package net.wdc.core.debug.diagnostics;

import java.util.List;

public record WDCSectionDump(
        int sectionY,
        String storageMode,
        int occupied,
        int paletteSize,
        long version,
        long estimatedBytes,
        List<WDCPaletteEntry> palette
) {
}
