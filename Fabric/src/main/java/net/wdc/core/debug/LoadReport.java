package net.wdc.core.debug;

import net.wdc.core.config.CapacityConfig;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.WDCCacheProvider;
import net.wdc.core.world.cache.CacheStats;
import net.wdc.core.world.capacity.CapacityController;
import net.wdc.core.world.capacity.LiveLimit;
import net.wdc.core.world.governor.IngestionGovernor;
import net.wdc.core.world.governor.IngestionGovernorProvider;
import net.wdc.core.world.governor.IngestionPerformanceConfig;
import net.wdc.core.world.governor.TickBudget;
import net.wdc.core.world.spill.DelayedSpillPolicy;
import net.wdc.core.world.spill.DelayedSpillPolicyProvider;

import java.util.ArrayList;
import java.util.List;

public final class LoadReport {

    public record Snapshot(
            int liveTotal,
            int liveLimit,
            int staleTotal,
            int ingestQueueDepth,
            int ingestQueueCapacity,
            int deferredInQueue,
            long deferredTotal,
            long spillDeferred,
            long liveWaiting
    ) {
        public boolean liveFull() { return liveTotal >= liveLimit; }
    }

    private LoadReport() {}

    public static Snapshot capture() {
        WDCCache cache = WDCCacheProvider.getIfPresent();
        IngestionGovernor governor = IngestionGovernorProvider.get();
        DelayedSpillPolicy spill = DelayedSpillPolicyProvider.get();

        int live = 0;
        int stale = 0;
        if (cache != null) {
            CacheStats stats = cache.stats();
            live = stats.liveEntries();
            stale = stats.staleEntries();
        }
        IngestionPerformanceConfig cfg = governor == null ? new IngestionPerformanceConfig() : governor.config();
        LiveLimit.Usage usage = LiveLimit.usage(live, cfg);
        return new Snapshot(
                usage.total(), usage.limit(),
                stale,
                governor == null ? 0 : governor.queueDepth(),
                governor == null ? cfg.queueCapacity : governor.queueCapacity(),
                governor == null ? 0 : governor.deferredInQueue(),
                WDCMetrics.get(WDCMetrics.Counter.GOVERNOR_DEFERRED),
                spill == null ? 0L : spill.ioStats().deferred(),
                WDCMetrics.get(WDCMetrics.Counter.GOVERNOR_LIVE_WAITING));
    }

    public static List<String> f3Lines(Snapshot s) {
        List<String> lines = new ArrayList<>();
        lines.add(liveLine(s));
        lines.add("§7Queue: §f" + s.ingestQueueDepth() + "/" + s.ingestQueueCapacity()
                + " §7deferred: §e" + s.deferredInQueue());
        return lines;
    }

    public static List<String> statusLines(Snapshot s) {
        List<String> lines = new ArrayList<>();
        lines.add(liveLine(s));
        IngestionGovernor governor = IngestionGovernorProvider.get();
        if (governor != null) {
            lines.add("§7LIVE release: §fonly when not required, after §e" + governor.config().liveReleaseGraceSeconds + "s §fidle");
        }
        lines.add("§7STALE retained: §e" + s.staleTotal());
        lines.add("§7Ingest queue: §f" + s.ingestQueueDepth() + "/" + s.ingestQueueCapacity()
                + " §7deferred now/total: §e" + s.deferredInQueue() + "§7/§e" + s.deferredTotal());
        lines.add("§7Waiting for room: §e" + s.liveWaiting());
        CapacityConfig capacity = CapacityController.runtime();
        lines.add("§7Stale max: §f" + capacity.staleMax + " §7Eviction batch: §f" + capacity.evictionBatch
                + " §7Spill queue: §f" + capacity.spillQueueLength + " §7Spill deferred: §e" + s.spillDeferred());
        return lines;
    }

    public static String budgetLine() {
        IngestionGovernor governor = IngestionGovernorProvider.get();
        if (governor == null) return null;
        return budgetLine(governor.tickBudget().snapshot());
    }

    public static String budgetLine(TickBudget.Snapshot budget) {
        String head = "§7WDC Budget: max allowed §c" + ms(budget.maxAllowedNanos()) + "ms";
        if (!budget.measured()) {
            return head + " §7| used §a-§7 | soft remaining §e" + budget.percent() + "% of the remaining tick";
        }
        return head + " §7| used §a" + ms(budget.usedNanos()) + "ms §7| soft remaining §e" + ms(budget.softRemainingNanos())
                + "ms §7(slice " + ms(budget.sliceNanos()) + "ms" + (budget.late() ? ", late tick" : "") + ")";
    }

    public static List<String> coreCostLines() {
        IngestionGovernor governor = IngestionGovernorProvider.get();
        if (governor == null) return List.of();
        List<String> lines = new ArrayList<>(2);
        lines.add(memoryLine(governor.memorySnapshot()));
        lines.add(cpuLine(governor.cpuMeter().snapshot()));
        return lines;
    }

    public static String memoryLine(CoreMemoryMeter.Snapshot memory) {
        if (!memory.measured()) return "§7WDC Mem: §fmeasuring...";
        return "§7WDC Mem: §f" + DiagnosticsReport.bytes(memory.bytes()) + " §7(" + memory.chunks() + " chunks, Core only)";
    }

    public static String cpuLine(CoreCpuMeter.Snapshot cpu) {
        if (!cpu.measured()) return "§7WDC CPU: §fmeasuring...";
        String color = cpu.percentOfTick() < 5.0 ? "§a" : cpu.percentOfTick() < 10.0 ? "§e" : "§c";
        return "§7WDC CPU: " + color + String.format(java.util.Locale.ROOT, "%.1f", cpu.percentOfTick()) + "% §7("
                + String.format(java.util.Locale.ROOT, "%.2f", cpu.millisPerTick()) + " ms/tick, peak "
                + String.format(java.util.Locale.ROOT, "%.1f", cpu.peakMillis()) + " ms)";
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    private static String liveLine(Snapshot s) {
        return "§7LIVE: §a" + s.liveTotal() + "§7/§f" + s.liveLimit()
                + (s.liveFull() ? " §c(full - new chunks wait)" : "");
    }
}
