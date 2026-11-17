package net.wdc.core.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OpsReport {
    private OpsReport() {}

    public static List<String> lines() {
        return lines(WDCOps.sample(), true, true);
    }

    public static List<String> panelLines() {
        return lines(WDCOps.sample(), OpsPanel.isExternalVisible(), OpsPanel.isInternalVisible());
    }

    public static List<String> lines(WDCOps.Snapshot s, boolean showExternal, boolean showInternal) {
        List<String> lines = new ArrayList<>();
        if (!showExternal && !showInternal) return lines;
        lines.add("§b[WorldData Ops]");
        if (showExternal) {
            lines.add("§7Mods → Core (asked by other mods): §a" + rate(s.external().perSecond()) + "/s §7peak §e"
                    + rate(s.peakExternalPerSecond()) + " §7total §f" + count(s.external().total()));
            lines.add("§7  Queries §a" + rate(s.query().perSecond()) + "/s §7(§f" + count(s.query().total())
                    + "§7) Requests §a" + rate(s.request().perSecond()) + "/s §7(§f" + count(s.request().total()) + "§7)");
            long submitted = WDCMetrics.get(WDCMetrics.Counter.REQUESTS_SUBMITTED);
            long finished = WDCMetrics.get(WDCMetrics.Counter.REQUESTS_COMPLETED)
                    + WDCMetrics.get(WDCMetrics.Counter.REQUESTS_CANCELED)
                    + WDCMetrics.get(WDCMetrics.Counter.REQUESTS_EXPIRED)
                    + WDCMetrics.get(WDCMetrics.Counter.REQUESTS_FAILED);
            lines.add("§7  Requests submitted §f" + count(submitted) + " §7in flight §e" + count(Math.max(0L, submitted - finished)));
        }
        if (showInternal) {
            lines.add("§7Core internal (work Core did itself): §a" + rate(s.internal().perSecond()) + "/s §7peak §e"
                    + rate(s.peakInternalPerSecond()) + " §7total §f" + count(s.internal().total()));
            lines.add("§7  Ingest §a" + rate(s.ingest().perSecond()) + "/s §7(§f" + count(s.ingest().total())
                    + "§7) Derived builds §a" + rate(s.demand().perSecond()) + "/s §7(§f" + count(s.demand().total()) + "§7)");
            lines.add("§7  Spill §a" + rate(s.spill().perSecond()) + "/s §7(§f" + count(s.spill().total())
                    + "§7) Bridge §a" + rate(s.bridge().perSecond()) + "/s §7(§f" + count(s.bridge().total())
                    + "§7) Governor §a" + rate(s.governor().perSecond()) + "/s §7(§f" + count(s.governor().total()) + "§7)");
        }
        return lines;
    }

    static String rate(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
