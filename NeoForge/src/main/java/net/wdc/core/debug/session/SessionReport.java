package net.wdc.core.debug.session;

import net.wdc.core.redirect.RedirectCounts;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SessionReport {

    public static final int TOP_CALLERS = 15;
    public static final int TOP_APIS = 8;

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", Locale.ROOT);

    private SessionReport() {}

    public static String fileName(SessionResult result) {
        String state = result.redirectChanged() ? "mixed" : result.redirectAtStart() ? "on" : "off";
        return "wdc-session-" + result.startedAt().format(FILE_STAMP) + "-redirect-" + state + ".txt";
    }

    public static List<String> lines(SessionResult result) {
        List<String> out = new ArrayList<>();
        out.add("WorldData Core test session");
        out.add("started: " + result.startedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        out.add("ended: " + result.endedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        out.add(String.format(Locale.ROOT, "duration_s: %.1f", result.actualSeconds()));
        out.add("requested_s: " + result.requestedSeconds());
        out.add("ended_by: " + result.reason().label());
        out.add("redirect_at_start: " + onOff(result.redirectAtStart()));
        out.add("redirect_at_end: " + onOff(result.redirectAtEnd()));
        if (result.redirectChanged()) out.add("redirect_changed_during_session: yes");
        out.add("");
        ticks(out, result.ticks());
        out.add("");
        redirect(out, result);
        out.add("");
        callers(out, result.callers());
        out.add("");
        notes(out, result);
        return out;
    }

    private static void ticks(List<String> out, TickStats.Snapshot ticks) {
        out.add("MSPT (server tick ms)");
        out.add("  samples: " + ticks.samples());
        if (ticks.samples() == 0L) {
            out.add("  min: n/a");
            out.add("  avg: n/a");
            out.add("  max: n/a");
            out.add("  note: no complete server tick was sampled (the session ended inside its first tick)");
            return;
        }
        out.add(String.format(Locale.ROOT, "  min: %.3f", ticks.minMillis()));
        out.add(String.format(Locale.ROOT, "  avg: %.3f", ticks.avgMillis()));
        out.add(String.format(Locale.ROOT, "  max: %.3f", ticks.maxMillis()));
        out.add("  ticks_over_50ms: " + ticks.overBudget());
    }

    private static void redirect(List<String> out, SessionResult result) {
        RedirectCounts counts = result.redirect();
        out.add("Redirect (session window)");
        out.add("  served: " + counts.served());
        out.add("  fallback: " + counts.fallback());
        out.add("  getBlockState served/fallback: " + counts.blockServed() + "/" + counts.blockFallback());
        out.add("  getFluidState served/fallback: " + counts.fluidServed() + "/" + counts.fluidFallback());
        if (!result.redirectAtStart() && !result.redirectAtEnd() && !result.redirectChanged()) {
            out.add("  note: the redirect was off for the whole session, so nothing was counted");
        }
    }

    private static void callers(List<String> out, CallerWindow window) {
        out.add("WorldData Core API callers (top entries)");
        if (!window.available()) {
            out.add("  not recorded: " + window.note());
            return;
        }
        out.add("  total_calls: " + window.totalCalls()
                + (window.estimated() ? " (caller split estimated from 1 in " + window.sampleEvery() + " calls; totals are exact)" : ""));
        if (window.nestedCalls() > 0L) {
            out.add("  nested_calls: " + window.nestedCalls() + " (reads made inside Core's own spatial calls, not attributed)");
        }
        if (window.callers().isEmpty()) {
            out.add("  (no calls from mods during the session)");
        }
        int shown = 0;
        for (CallerWindow.Row row : window.callers()) {
            if (shown++ >= TOP_CALLERS) break;
            out.add(String.format(Locale.ROOT, "  %-44s %d", trim(row.caller(), 44), row.calls()));
        }
        if (window.callers().size() > TOP_CALLERS) {
            out.add("  ... and " + (window.callers().size() - TOP_CALLERS) + " more callers");
        }
        if (!window.apis().isEmpty()) {
            out.add("  busiest API calls");
            int apiShown = 0;
            for (CallerWindow.ApiRowView row : window.apis()) {
                if (apiShown++ >= TOP_APIS) break;
                out.add(String.format(Locale.ROOT, "    %-40s %d", row.api().label(), row.calls()));
            }
        }
        if (!window.note().isEmpty()) out.add("  note: " + window.note());
    }

    private static void notes(List<String> out, SessionResult result) {
        out.add("Notes");
        out.add("  Compare two sessions (redirect off, then on) at the same place with the same activity.");
        out.add("  This file covers server tick time and calls into WorldData Core only. Use Spark for the per-mod CPU breakdown.");
        out.add("  MSPT is measured from the start of the server tick to its end, so the wait between ticks is not included.");
        if (result.redirectChanged()) {
            out.add("  The redirect was switched during this session, so its counts mix both states. Do not compare this file.");
        }
        if (result.reason() == SessionResult.EndReason.SERVER_STOPPING) {
            out.add("  The server was stopping, so the session is shorter than requested.");
        }
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    private static String trim(String text, int width) {
        if (text.length() <= width) return text;
        return text.substring(0, Math.max(1, width - 3)) + "...";
    }
}
