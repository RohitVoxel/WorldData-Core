package net.wdc.core.debug.monitor;

import java.util.List;

public record MonitorSnapshot(
        boolean enabled,
        long activeNanos,
        int sampleEvery,
        int maxCallers,
        int callerCount,
        boolean overflowed,
        long totalCalls,
        long nestedCalls,
        long sampledCalls,
        long walks,
        double averageWalkNanos,
        List<ApiRow> apis,
        List<CallerRow> callers,
        List<PairRow> pairs) {

    public record ApiRow(MonitorApi api, long calls, long nested, long[] outcomes) {

        public long known() {
            long sum = 0L;
            for (int i = 0; i < outcomes.length; i++) {
                if (MonitorOutcome.of(i).known()) sum += outcomes[i];
            }
            return sum;
        }
    }

    public record CallerRow(String caller, long calls, long repeats, long sampled, long knownSampled, long notServedSampled) {}

    public record PairRow(String caller, MonitorApi api, long calls, long repeats) {}

    public double callsPerSecond() {
        if (activeNanos <= 0L) return 0.0;
        return totalCalls / (activeNanos / 1_000_000_000.0);
    }

    public boolean estimated() {
        return sampleEvery > 1;
    }

    public long callsOf(MonitorApi api) {
        for (ApiRow row : apis) {
            if (row.api() == api) return row.calls();
        }
        return 0L;
    }

    public long callsOf(String caller) {
        for (CallerRow row : callers) {
            if (row.caller().equals(caller)) return row.calls();
        }
        return 0L;
    }

    public long callsOf(String caller, MonitorApi api) {
        for (PairRow row : pairs) {
            if (row.caller().equals(caller) && row.api() == api) return row.calls();
        }
        return 0L;
    }
}
