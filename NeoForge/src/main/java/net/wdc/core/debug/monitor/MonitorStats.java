package net.wdc.core.debug.monitor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

public final class MonitorStats {

    public static final long NO_KEY = Long.MIN_VALUE;
    public static final String OVERFLOW = "(other callers)";
    public static final int REPEAT_SLOTS = 1024;

    private static final int APIS = MonitorApi.COUNT;
    private static final int OUTCOMES = MonitorOutcome.COUNT;

    private static final class Caller {
        final String name;
        final int index;
        final AtomicLongArray sampled = new AtomicLongArray(APIS);
        final AtomicLongArray repeats = new AtomicLongArray(APIS);
        final AtomicLongArray outcomes = new AtomicLongArray(OUTCOMES);

        Caller(String name, int index) {
            this.name = name;
            this.index = index;
        }

        void clear() {
            for (int i = 0; i < APIS; i++) {
                sampled.set(i, 0L);
                repeats.set(i, 0L);
            }
            for (int i = 0; i < OUTCOMES; i++) outcomes.set(i, 0L);
        }

        long sampledTotal() {
            long sum = 0L;
            for (int i = 0; i < APIS; i++) sum += sampled.get(i);
            return sum;
        }
    }

    private final int maxCallers;
    private final long repeatWindowNanos;
    private final LongAdder[] calls = adders(APIS);
    private final LongAdder[] nested = adders(APIS);
    private final LongAdder[] sampledByApi = adders(APIS);
    private final LongAdder[][] outcomesByApi = new LongAdder[APIS][];
    private final ConcurrentHashMap<String, Caller> callers = new ConcurrentHashMap<>();
    private final Caller overflow;
    private final AtomicLongArray repeatKeys = new AtomicLongArray(REPEAT_SLOTS);
    private final AtomicLongArray repeatTimes = new AtomicLongArray(REPEAT_SLOTS);
    private final LongAdder walks = new LongAdder();
    private final LongAdder walkNanos = new LongAdder();

    public MonitorStats(int maxCallers, long repeatWindowNanos) {
        if (maxCallers < 1) throw new IllegalArgumentException("maxCallers must be at least 1");
        this.maxCallers = maxCallers;
        this.repeatWindowNanos = Math.max(0L, repeatWindowNanos);
        this.overflow = new Caller(OVERFLOW, maxCallers);
        for (int i = 0; i < APIS; i++) outcomesByApi[i] = adders(OUTCOMES);
    }

    private static LongAdder[] adders(int count) {
        LongAdder[] out = new LongAdder[count];
        for (int i = 0; i < count; i++) out[i] = new LongAdder();
        return out;
    }

    public int maxCallers() {
        return maxCallers;
    }

    public int callerCount() {
        return callers.size();
    }

    public void count(MonitorApi api, MonitorOutcome outcome) {
        int a = api.ordinal();
        calls[a].increment();
        if (outcome != MonitorOutcome.UNKNOWN) outcomesByApi[a][outcome.ordinal()].increment();
    }

    public void countNested(MonitorApi api) {
        nested[api.ordinal()].increment();
    }

    public void addWalk(long nanos) {
        walks.increment();
        if (nanos > 0L) walkNanos.add(nanos);
    }

    public void sample(String callerName, MonitorApi api, MonitorOutcome outcome, long key, long nowNanos) {
        Caller caller = callerFor(callerName);
        int a = api.ordinal();
        caller.sampled.incrementAndGet(a);
        caller.outcomes.incrementAndGet(outcome.ordinal());
        sampledByApi[a].increment();
        if (key != NO_KEY && repeated(caller.index, a, key, nowNanos)) caller.repeats.incrementAndGet(a);
    }

    private Caller callerFor(String name) {
        Caller found = callers.get(name);
        if (found != null) return found;
        if (callers.size() >= maxCallers) return overflow;
        synchronized (callers) {
            found = callers.get(name);
            if (found != null) return found;
            int count = callers.size();
            if (count >= maxCallers) return overflow;
            Caller created = new Caller(name, count);
            callers.put(name, created);
            return created;
        }
    }

    private boolean repeated(int callerIndex, int api, long key, long now) {
        long mixed = key * 0x9E3779B97F4A7C15L;
        mixed ^= ((long) callerIndex << 32) | (api + 1L);
        mixed *= 0xC2B2AE3D27D4EB4FL;
        mixed ^= mixed >>> 29;
        if (mixed == 0L) mixed = 1L;
        int slot = (int) (mixed ^ (mixed >>> 32)) & (REPEAT_SLOTS - 1);
        long previousKey = repeatKeys.get(slot);
        long previousTime = repeatTimes.get(slot);
        repeatKeys.set(slot, mixed);
        repeatTimes.set(slot, now);
        if (previousKey != mixed) return false;
        long age = now - previousTime;
        return age >= 0L && age <= repeatWindowNanos;
    }

    public void reset() {
        for (int i = 0; i < APIS; i++) {
            calls[i].reset();
            nested[i].reset();
            sampledByApi[i].reset();
            for (LongAdder adder : outcomesByApi[i]) adder.reset();
        }
        for (Caller caller : callers.values()) caller.clear();
        callers.clear();
        overflow.clear();
        for (int i = 0; i < REPEAT_SLOTS; i++) {
            repeatKeys.set(i, 0L);
            repeatTimes.set(i, 0L);
        }
        walks.reset();
        walkNanos.reset();
    }

    public long totalCalls() {
        long sum = 0L;
        for (LongAdder adder : calls) sum += adder.sum();
        return sum;
    }

    public long calls(MonitorApi api) {
        return calls[api.ordinal()].sum();
    }

    public long nested(MonitorApi api) {
        return nested[api.ordinal()].sum();
    }

    public MonitorSnapshot snapshot(int top, boolean enabled, int sampleEvery, long activeNanos) {
        int limit = Math.max(1, top);
        long[] sampledTotals = new long[APIS];
        long[] callTotals = new long[APIS];
        long sampledSum = 0L;
        for (int i = 0; i < APIS; i++) sampledTotals[i] = sampledByApi[i].sum();
        for (int i = 0; i < APIS; i++) {
            callTotals[i] = calls[i].sum();
            sampledSum += sampledTotals[i];
        }
        double[] factor = new double[APIS];
        for (int i = 0; i < APIS; i++) {
            factor[i] = sampledTotals[i] == 0L ? 0.0 : Math.max(1.0, (double) callTotals[i] / sampledTotals[i]);
        }

        List<MonitorSnapshot.ApiRow> apiRows = new ArrayList<>();
        long total = 0L;
        long nestedTotal = 0L;
        for (int i = 0; i < APIS; i++) {
            long n = nested[i].sum();
            total += callTotals[i];
            nestedTotal += n;
            if (callTotals[i] == 0L && n == 0L) continue;
            long[] outcomes = new long[OUTCOMES];
            for (int o = 0; o < OUTCOMES; o++) outcomes[o] = outcomesByApi[i][o].sum();
            apiRows.add(new MonitorSnapshot.ApiRow(MonitorApi.of(i), callTotals[i], n, outcomes));
        }
        apiRows.sort(Comparator.comparingLong(MonitorSnapshot.ApiRow::calls).reversed()
                .thenComparing(row -> row.api().ordinal()));

        List<Caller> all = new ArrayList<>(callers.values());
        if (overflow.sampledTotal() > 0L) all.add(overflow);
        List<MonitorSnapshot.CallerRow> callerRows = new ArrayList<>();
        List<MonitorSnapshot.PairRow> pairRows = new ArrayList<>();
        for (Caller caller : all) {
            long estimated = 0L;
            long estimatedRepeats = 0L;
            for (int i = 0; i < APIS; i++) {
                long s = caller.sampled.get(i);
                if (s == 0L) continue;
                long calls = Math.round(s * factor[i]);
                long repeats = Math.min(calls, Math.round(caller.repeats.get(i) * factor[i]));
                estimated += calls;
                estimatedRepeats += repeats;
                pairRows.add(new MonitorSnapshot.PairRow(caller.name, MonitorApi.of(i), calls, repeats));
            }
            long known = 0L;
            long notServed = 0L;
            for (int o = 0; o < OUTCOMES; o++) {
                MonitorOutcome outcome = MonitorOutcome.of(o);
                if (!outcome.known()) continue;
                long n = caller.outcomes.get(o);
                known += n;
                if (!outcome.served()) notServed += n;
            }
            callerRows.add(new MonitorSnapshot.CallerRow(caller.name, estimated, estimatedRepeats, caller.sampledTotal(), known, notServed));
        }
        callerRows.sort(Comparator.comparingLong(MonitorSnapshot.CallerRow::calls).reversed()
                .thenComparing(MonitorSnapshot.CallerRow::caller));
        pairRows.sort(Comparator.comparingLong(MonitorSnapshot.PairRow::calls).reversed()
                .thenComparing(MonitorSnapshot.PairRow::caller).thenComparing(row -> row.api().ordinal()));

        long walkCount = walks.sum();
        double average = walkCount == 0L ? 0.0 : (double) walkNanos.sum() / walkCount;
        return new MonitorSnapshot(enabled, activeNanos, sampleEvery, maxCallers, callers.size(), overflow.sampledTotal() > 0L,
                total, nestedTotal, sampledSum, walkCount, average,
                List.copyOf(apiRows.subList(0, Math.min(limit, apiRows.size()))),
                List.copyOf(callerRows.subList(0, Math.min(limit, callerRows.size()))),
                List.copyOf(pairRows.subList(0, Math.min(limit, pairRows.size()))));
    }
}
