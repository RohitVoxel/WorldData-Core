package net.wdc.core.debug.monitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MonitorReport {

    public static final int DEFAULT_TOP = 8;
    public static final int MAX_TOP = 25;

    private MonitorReport() {}

    public static List<String> lines(MonitorSnapshot snapshot, int top) {
        List<String> lines = new ArrayList<>();
        boolean estimated = snapshot.estimated();
        String tilde = estimated ? "~" : "";
        lines.add("WorldData Core API monitor: " + (snapshot.enabled() ? "ON" : "OFF") + ", enabled for "
                + duration(snapshot.activeNanos()) + " since the last reset");
        if (snapshot.totalCalls() == 0L && snapshot.nestedCalls() == 0L) {
            lines.add(snapshot.enabled()
                    ? "No API calls seen yet. Let the game run, then use /worlddata monitor report again."
                    : "Nothing recorded. Turn it on with /worlddata monitor on, let the game run, then use /worlddata monitor report.");
            return lines;
        }
        lines.add("Calls: " + count(snapshot.totalCalls()) + " (" + rate(snapshot.callsPerSecond()) + "/s) from mods and Core users; "
                + count(snapshot.nestedCalls()) + " more were made by Core's own spatial calls and are listed apart");
        lines.add("Callers: " + (estimated ? "estimated from 1 in " + snapshot.sampleEvery() + " calls (API totals are exact)" : "every call attributed")
                + "; stack walk about " + micros(snapshot.averageWalkNanos()) + "; " + snapshot.callerCount() + " of "
                + snapshot.maxCallers() + " caller slots used" + (snapshot.overflowed() ? ", others grouped as " + MonitorStats.OVERFLOW : ""));
        int limit = Math.max(1, Math.min(MAX_TOP, top));
        lines.add("Top callers (calls, share, repeated, not served)");
        int rank = 1;
        for (MonitorSnapshot.CallerRow row : snapshot.callers()) {
            if (rank > limit) break;
            lines.add(String.format(Locale.ROOT, "%2d. %-34s %s%s  %5s  repeat %s  not served %s", rank++, shorten(row.caller(), 34), tilde,
                    count(row.calls()), share(row.calls(), snapshot.totalCalls()), percent(row.repeats(), row.calls()),
                    percent(row.notServedSampled(), row.knownSampled())));
        }
        lines.add("Top APIs (calls, outcome)");
        rank = 1;
        for (MonitorSnapshot.ApiRow row : snapshot.apis()) {
            if (rank > limit) break;
            lines.add(String.format(Locale.ROOT, "%2d. %-28s %s  %s%s", rank++, row.api().label(), count(row.calls()), outcomeText(row),
                    row.nested() > 0L ? "  +" + count(row.nested()) + " nested" : ""));
        }
        if (!snapshot.pairs().isEmpty()) {
            lines.add("Top caller to API pairs");
            rank = 1;
            for (MonitorSnapshot.PairRow row : snapshot.pairs()) {
                if (rank > limit) break;
                lines.add(String.format(Locale.ROOT, "%2d. %s -> %s  %s%s  repeat %s", rank++, shorten(row.caller(), 34), row.api().label(),
                        tilde, count(row.calls()), percent(row.repeats(), row.calls())));
            }
        }
        lines.add("repeat = same caller, API and target again within 250 ms; not served = chunk was unloaded, pending or unavailable");
        return lines;
    }

    private static String outcomeText(MonitorSnapshot.ApiRow row) {
        long known = row.known();
        if (known == 0L) return "";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < row.outcomes().length; i++) {
            MonitorOutcome outcome = MonitorOutcome.of(i);
            if (!outcome.known() || row.outcomes()[i] == 0L) continue;
            if (text.length() > 0) text.append(' ');
            text.append(outcome.name().toLowerCase(Locale.ROOT)).append(' ').append(percent(row.outcomes()[i], known));
        }
        return text.toString();
    }

    static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    static String rate(double perSecond) {
        if (perSecond >= 100.0) return String.format(Locale.ROOT, "%,.0f", perSecond);
        return String.format(Locale.ROOT, "%.1f", perSecond);
    }

    static String percent(long part, long whole) {
        if (whole <= 0L) return "0%";
        double value = 100.0 * part / whole;
        if (value > 0.0 && value < 0.1) return "<0.1%";
        return String.format(Locale.ROOT, value >= 10.0 ? "%.0f%%" : "%.1f%%", value);
    }

    static String share(long part, long whole) {
        return percent(part, whole);
    }

    static String micros(double nanos) {
        if (nanos <= 0.0) return "n/a";
        return String.format(Locale.ROOT, "%.1f us", nanos / 1000.0);
    }

    static String duration(long nanos) {
        long seconds = Math.max(0L, nanos / 1_000_000_000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long rest = seconds % 60L;
        if (hours > 0L) return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, rest);
        if (minutes > 0L) return String.format(Locale.ROOT, "%dm %02ds", minutes, rest);
        return rest + "s";
    }

    static String shorten(String text, int width) {
        if (text.length() <= width) return text;
        return text.substring(0, Math.max(1, width - 3)) + "...";
    }
}
