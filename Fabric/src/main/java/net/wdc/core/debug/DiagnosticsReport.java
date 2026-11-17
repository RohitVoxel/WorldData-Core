package net.wdc.core.debug;

import net.wdc.core.debug.diagnostics.WDCCacheStats;
import net.wdc.core.debug.diagnostics.WDCDiagnosticsSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DiagnosticsReport {

    private DiagnosticsReport() {}

    public static List<String> lines(WDCDiagnosticsSnapshot s) {
        WDCCacheStats cache = s.cache();
        List<String> lines = new ArrayList<>();
        lines.add("§b--- Cache Diagnostics ---");
        lines.add("§7Entries: §f" + cache.entries() + " §7(live §a" + cache.liveEntries() + "§7 / stale §e"
                + cache.staleEntries() + "§7) sections §f" + s.sectionCount());
        lines.add("§7Lookups: §f" + cache.lookups() + " §7hit §a" + percent(cache.hitRate()) + " §7miss §c"
                + percent(cache.missRate()) + " §7rebuilds §f" + cache.rebuilds() + " §7evictions §c" + cache.evictions());
        lines.add("§7Memory estimate: §f" + bytes(s.memoryEstimateBytes()) + " §7block states §f" + s.registeredStates());
        lines.add("§7Ingest: §f" + ms(s.averageIngestMillis()) + " ms avg §7/ §f" + ms(s.p99IngestMillis())
                + " ms p99 §7(" + s.ingestionSamples() + " samples)");
        return lines;
    }

    static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
    }

    static String ms(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    static String bytes(long value) {
        if (value < 1024L) return value + " B";
        if (value < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KiB", value / 1024.0);
        return String.format(Locale.ROOT, "%.2f MiB", value / (1024.0 * 1024.0));
    }
}
