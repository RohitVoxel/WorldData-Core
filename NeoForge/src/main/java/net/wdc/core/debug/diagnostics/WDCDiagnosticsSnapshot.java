package net.wdc.core.debug.diagnostics;

import net.wdc.core.debug.LoadReport;

public record WDCDiagnosticsSnapshot(
        WDCCacheStats cache,
        LoadReport.Snapshot load,
        long memoryEstimateBytes,
        int sectionCount,
        int registeredStates,
        long ingestionSamples,
        double averageIngestMillis,
        double p99IngestMillis
) {
}
