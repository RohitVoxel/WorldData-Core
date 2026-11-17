package net.wdc.core.debug.session;

import net.wdc.core.debug.monitor.MonitorApi;
import net.wdc.core.debug.monitor.MonitorSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record CallerWindow(boolean available, String note, boolean estimated, int sampleEvery, long totalCalls,
                           long nestedCalls, List<Row> callers, List<ApiRowView> apis) {

    public record Row(String caller, long calls) {}

    public record ApiRowView(MonitorApi api, long calls) {}

    public static CallerWindow unavailable(String note) {
        return new CallerWindow(false, note, false, 1, 0L, 0L, List.of(), List.of());
    }

    public static CallerWindow between(MonitorSnapshot start, MonitorSnapshot end, boolean monitorReset, String extraNote) {
        boolean reset = monitorReset || end.totalCalls() < start.totalCalls() || end.nestedCalls() < start.nestedCalls();
        Map<String, Long> baseCallers = new HashMap<>();
        Map<MonitorApi, Long> baseApis = new HashMap<>();
        long baseTotal = 0L;
        long baseNested = 0L;
        if (!reset) {
            for (MonitorSnapshot.CallerRow row : start.callers()) baseCallers.put(row.caller(), row.calls());
            for (MonitorSnapshot.ApiRow row : start.apis()) baseApis.put(row.api(), row.calls());
            baseTotal = start.totalCalls();
            baseNested = start.nestedCalls();
        }
        List<Row> callers = new ArrayList<>();
        for (MonitorSnapshot.CallerRow row : end.callers()) {
            long delta = row.calls() - baseCallers.getOrDefault(row.caller(), 0L);
            if (delta > 0L) callers.add(new Row(row.caller(), delta));
        }
        callers.sort(Comparator.comparingLong(Row::calls).reversed().thenComparing(Row::caller));
        List<ApiRowView> apis = new ArrayList<>();
        for (MonitorSnapshot.ApiRow row : end.apis()) {
            long delta = row.calls() - baseApis.getOrDefault(row.api(), 0L);
            if (delta > 0L) apis.add(new ApiRowView(row.api(), delta));
        }
        apis.sort(Comparator.comparingLong(ApiRowView::calls).reversed().thenComparing(view -> view.api().ordinal()));
        StringBuilder note = new StringBuilder();
        if (reset) note.append("the API monitor was reset during the session, so only the part after the reset is counted");
        if (extraNote != null && !extraNote.isEmpty()) {
            if (note.length() > 0) note.append("; ");
            note.append(extraNote);
        }
        return new CallerWindow(true, note.toString(), end.sampleEvery() > 1, end.sampleEvery(),
                Math.max(0L, end.totalCalls() - baseTotal), Math.max(0L, end.nestedCalls() - baseNested),
                List.copyOf(callers), List.copyOf(apis));
    }
}
