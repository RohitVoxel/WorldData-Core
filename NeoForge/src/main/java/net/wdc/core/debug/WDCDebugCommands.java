package net.wdc.core.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.wdc.core.api.WDCApiVersion;
import net.wdc.core.debug.diagnostics.WDCChunkDump;
import net.wdc.core.debug.diagnostics.WDCSectionDump;
import net.wdc.core.debug.diagnostics.CoreDiagnostics;
import net.wdc.core.debug.monitor.MonitorCommands;
import net.wdc.core.debug.session.SessionCommands;
import net.wdc.core.config.WDCConfig;
import net.wdc.core.config.BudgetConfig;
import net.wdc.core.config.CapacityConfig;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.WDCCacheProvider;
import net.wdc.core.world.capacity.CapacityController;
import net.wdc.core.world.change.WorldChangeTracker;
import net.wdc.core.world.change.WorldChangeTrackerProvider;
import net.wdc.core.world.governor.BudgetPolicy;
import net.wdc.core.world.governor.GovernorMetricsSnapshot;
import net.wdc.core.world.governor.IngestionGovernor;
import net.wdc.core.world.governor.IngestionGovernorProvider;
import net.wdc.core.world.governor.IngestionPerformanceConfig;
import net.wdc.core.world.governor.TickBudget;
import net.wdc.core.world.spill.DelayedSpillPolicy;
import net.wdc.core.world.spill.DelayedSpillPolicyProvider;
import net.wdc.core.world.spill.SpillIoStats;
import net.wdc.core.world.storage.ChunkRegistry;
import net.wdc.core.world.storage.ChunkRegistryProvider;
import net.wdc.core.world.storage.GlobalBlockStateRegistryProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WDCDebugCommands {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        NeoForge.EVENT_BUS.addListener(WDCDebugCommands::onRegisterCommands);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(buildRoot());
    }

    static LiteralArgumentBuilder<CommandSourceStack> buildRoot() {
        return Commands.literal("worlddata")
                .requires(info())
                .then(Commands.literal("help").executes(WDCDebugCommands::printHelp))
                .then(Commands.literal("stats").executes(WDCDebugCommands::printStats))
                .then(Commands.literal("gc").requires(admin()).executes(WDCDebugCommands::forceGC))
                .then(performanceSubtree())
                .then(capacitySubtree())
                .then(budgetSubtree())
                .then(MonitorCommands.subtree(admin()))
                .then(SessionCommands.subtree(admin()))
                .then(WDCHotCommands.pinSubtree(admin()))
                .then(WDCHotCommands.unpinSubtree(admin()))
                .then(WDCHotCommands.pinsCommand())
                .then(WDCHotCommands.redirectSubtree(admin()))
                .then(WDCHotCommands.overlaySubtree(admin()))
                .then(dumpSubtree().requires(admin()));
    }

    private static Predicate<CommandSourceStack> info() {
        return source -> source.hasPermission(WDCConfig.get().commands.infoPermissionLevel);
    }

    private static Predicate<CommandSourceStack> admin() {
        return source -> source.hasPermission(WDCConfig.get().commands.permissionLevel);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> dumpSubtree() {
        return Commands.literal("dump")
                .then(Commands.literal("here").executes(WDCDebugCommands::dumpHere))
                .then(Commands.literal("chunk")
                        .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                                .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                                        .executes(context -> dumpChunk(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "chunkX"),
                                                IntegerArgumentType.getInteger(context, "chunkZ"))))))
                .then(Commands.literal("section")
                        .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                                .then(Commands.argument("sectionY", IntegerArgumentType.integer())
                                        .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                                                .executes(context -> dumpSection(context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "chunkX"),
                                                        IntegerArgumentType.getInteger(context, "sectionY"),
                                                        IntegerArgumentType.getInteger(context, "chunkZ")))))));
    }

    private static CoreDiagnostics diagnostics() {
        return new CoreDiagnostics(WDCCacheProvider.getIfPresent(), GlobalBlockStateRegistryProvider.getOrCreate());
    }

    private static int dumpHere(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        var position = source.getPosition();
        return dumpChunk(source, ((int) Math.floor(position.x)) >> 4, ((int) Math.floor(position.z)) >> 4);
    }

    private static int dumpChunk(CommandSourceStack source, int chunkX, int chunkZ) {
        CoreDiagnostics diagnostics = diagnostics();
        if (!diagnostics.available()) {
            send(source, "§c[WorldData] Cache not initialized yet.");
            return 0;
        }
        String dim = source.getLevel().dimension().location().toString();
        Optional<WDCChunkDump> dump = diagnostics.dumpChunk(dim, chunkX, chunkZ);
        if (dump.isEmpty()) {
            send(source, "§e[WorldData] No cached data for chunk " + chunkX + ", " + chunkZ + " in " + dim + ".");
            return 0;
        }
        for (String line : DumpReport.chunkLines(dump.get())) send(source, line);
        return 1;
    }

    private static int dumpSection(CommandSourceStack source, int chunkX, int sectionY, int chunkZ) {
        CoreDiagnostics diagnostics = diagnostics();
        if (!diagnostics.available()) {
            send(source, "§c[WorldData] Cache not initialized yet.");
            return 0;
        }
        String dim = source.getLevel().dimension().location().toString();
        Optional<WDCSectionDump> dump = diagnostics.dumpSection(dim, chunkX, sectionY, chunkZ);
        if (dump.isEmpty()) {
            send(source, "§e[WorldData] No cached section " + chunkX + ", " + sectionY + ", " + chunkZ + " in " + dim + ".");
            return 0;
        }
        for (String line : DumpReport.sectionLines(chunkX, chunkZ, dump.get())) send(source, line);
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> performanceSubtree() {
        return Commands.literal("performance")
                .executes(WDCDebugCommands::performanceStatus)
                .then(Commands.literal("status").executes(WDCDebugCommands::performanceStatus))
                .then(Commands.literal("reset").requires(admin()).executes(WDCDebugCommands::performanceReset));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> budgetSubtree() {
        return Commands.literal("budget")
                .executes(WDCDebugCommands::budgetStatus)
                .then(Commands.literal("share").requires(admin())
                        .then(Commands.argument("percent", IntegerArgumentType.integer(BudgetConfig.MIN_SHARE_PERCENT, BudgetConfig.MAX_SHARE_PERCENT))
                                .executes(context -> {
                                    int percent = IntegerArgumentType.getInteger(context, "percent");
                                    return updateBudget(context, "Budget share set to " + percent + "% of the remaining tick",
                                            cfg -> cfg.ingestShareOfRemainingTickPercent = percent);
                                })))
                .then(Commands.literal("fixed").requires(admin())
                        .then(Commands.argument("ms", IntegerArgumentType.integer(1, BudgetConfig.ABSOLUTE_MAX_MS))
                                .executes(context -> {
                                    int ms = IntegerArgumentType.getInteger(context, "ms");
                                    return updateBudget(context, "Late-tick budget set to " + ms + " ms", cfg -> cfg.fixedMs = ms);
                                })))
                .then(Commands.literal("max").requires(admin())
                        .then(Commands.argument("ms", IntegerArgumentType.integer(1, BudgetConfig.ABSOLUTE_MAX_MS))
                                .executes(context -> {
                                    int ms = IntegerArgumentType.getInteger(context, "ms");
                                    return updateBudget(context, "Budget max allowed set to " + ms + " ms", cfg -> cfg.absoluteMaxMs = ms);
                                })))
                .then(Commands.literal("reset").requires(admin())
                        .executes(context -> {
                            BudgetConfig defaults = new BudgetConfig();
                            return updateBudget(context, "Budget reset to defaults", cfg -> {
                                cfg.absoluteMaxMs = defaults.absoluteMaxMs;
                                cfg.ingestShareOfRemainingTickPercent = defaults.ingestShareOfRemainingTickPercent;
                                cfg.fixedMs = defaults.fixedMs;
                            });
                        }));
    }

    private static int updateBudget(CommandContext<CommandSourceStack> context, String label, Consumer<BudgetConfig> mutate) {
        CommandSourceStack source = context.getSource();
        IngestionGovernor governor = requireGovernor(source);
        if (governor == null) return 0;
        BudgetConfig next = WDCConfig.get().budget.copy();
        mutate.accept(next);
        try {
            governor.applyBudget(next);
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal("§c[WorldData] " + invalid.getMessage() + ". Nothing was changed."));
            return 0;
        }
        WDCConfig.get().budget = next;
        WDCConfig.save();
        send(source, "§a[WorldData] " + label + " (saved to worlddata_core.json, effective next tick).");
        return 1;
    }

    private static int budgetStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        IngestionGovernor governor = requireGovernor(source);
        if (governor == null) return 0;
        BudgetPolicy policy = governor.tickBudget().policy();
        send(source, "§bWorldData Core budget");
        send(source, "§7Share of the remaining tick: §f" + policy.sharePercent() + "% §7late-tick fixed: §f"
                + nanosToMs(policy.fixedNanos()) + " ms §7max allowed: §f" + nanosToMs(policy.absoluteMaxNanos()) + " ms");
        send(source, budgetLastTickLine(governor.tickBudget().snapshot()));
        return 1;
    }

    private static long nanosToMs(long nanos) {
        return nanos / 1_000_000L;
    }

    private static int printHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        send(source, "§bWorldData Core §7API " + WDCApiVersion.asString());
        send(source, "§7Info commands (level " + WDCConfig.get().commands.infoPermissionLevel
                + "): §f/worlddata stats, performance, capacity show, budget, pins, monitor report, help");
        send(source, "§7Admin commands (level " + WDCConfig.get().commands.permissionLevel
                + "): §f/worlddata budget share|fixed|max|reset, capacity <setting> <value>, capacity reset,"
                + " performance reset, monitor on|off|reset|sample|save, session start [seconds]|stop|status, gc, dump, pin <x y z>|here|area, unpin <x y z>|here, redirect [on|off|reset], overlay [on|off|radius]");
        return 1;
    }

    private static String budgetLastTickLine(TickBudget.Snapshot budget) {
        if (!budget.measured()) return "§7Last tick: §fnot measured yet";
        return "§7Last tick: remaining §f" + fmtMs1(budget.remainingNanos()) + " ms §7→ slice §f"
                + fmtMs1(budget.sliceNanos()) + " ms §7→ used §f" + fmtMs1(budget.usedNanos()) + " ms";
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> capacitySubtree() {
        return Commands.literal("capacity")
                .executes(WDCDebugCommands::capacityStatus)
                .then(Commands.literal("show").executes(WDCDebugCommands::capacityStatus))
                .then(Commands.literal("status").executes(WDCDebugCommands::capacityStatus))
                .then(Commands.literal("reset").requires(admin()).executes(WDCDebugCommands::capacityReset))
                .then(valueLiteral("queue", 1, IngestionPerformanceConfig.MAX_QUEUE_CAPACITY, WDCDebugCommands::capacityQueue))
                .then(valueLiteral("live-max", 1, IngestionPerformanceConfig.MAX_LIVE_LIMIT, WDCDebugCommands::capacityLiveLimit))
                .then(valueLiteral("live-limit", 1, IngestionPerformanceConfig.MAX_LIVE_LIMIT, WDCDebugCommands::capacityLiveLimit))
                .then(valueLiteral("live-grace", 0, IngestionPerformanceConfig.MAX_LIVE_RELEASE_GRACE_SECONDS, WDCDebugCommands::capacityLiveGrace))
                .then(valueLiteral("deferred-max", 0, IngestionPerformanceConfig.MAX_QUEUE_CAPACITY, WDCDebugCommands::capacityDeferredMax))
                .then(valueLiteral("stale-max", 0, CapacityConfig.MAX_STALE, WDCDebugCommands::capacityStaleMax))
                .then(valueLiteral("eviction-batch", 1, CapacityConfig.MAX_EVICTION_BATCH, WDCDebugCommands::capacityEvictionBatch))
                .then(valueLiteral("spill-queue", 1, CapacityConfig.MAX_SPILL_QUEUE, WDCDebugCommands::capacitySpillQueue));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> valueLiteral(
            String name, int min, int max, com.mojang.brigadier.Command<CommandSourceStack> handler) {
        return Commands.literal(name).requires(admin())
                .then(Commands.argument("value", IntegerArgumentType.integer(min, max)).executes(handler));
    }

    private static int capacityStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        LoadReport.Snapshot snapshot = LoadReport.capture();
        send(source, "§b=== WorldData Capacity ===");
        for (String line : LoadReport.statusLines(snapshot)) send(source, line);
        CapacityConfig ceilings = CapacityController.runtime();
        send(source, "§7Ceilings: §fLIVE " + ceilings.liveMaxCeiling + " §7STALE §f" + ceilings.staleMaxCeiling
                + " §7queue §f" + ceilings.ingestQueueLengthMax);
        return 1;
    }

    private static int capacityReset(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        IngestionGovernor governor = requireGovernor(source);
        if (governor == null) return 0;
        governor.applyConfig(WDCConfig.get().performance.copy());
        CapacityController.update(WDCConfig.get().capacity.copy(), WDCCacheProvider.get(), DelayedSpillPolicyProvider.get());
        send(source, "§a[WorldData] Capacity settings reset to configured defaults.");
        return 1;
    }

    private static int updateGovernor(CommandContext<CommandSourceStack> context, String label, Consumer<IngestionPerformanceConfig> mutate) {
        CommandSourceStack source = context.getSource();
        IngestionGovernor governor = requireGovernor(source);
        if (governor == null) return 0;
        IngestionPerformanceConfig runtime = governor.config().copy();
        mutate.accept(runtime);
        try {
            governor.applyConfig(runtime);
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal("§c[WorldData] " + invalid.getMessage()));
            return 0;
        }
        send(source, "§a[WorldData] " + label + " (this session).");
        return 1;
    }

    private static int capacityQueue(CommandContext<CommandSourceStack> context) {
        int value = IntegerArgumentType.getInteger(context, "value");
        int ceiling = CapacityController.runtime().ingestQueueLengthMax;
        if (value > ceiling) {
            context.getSource().sendFailure(Component.literal("§c[WorldData] Queue length is capped at " + ceiling + " by the capacity config."));
            return 0;
        }
        return updateGovernor(context, "Ingest queue length set to " + value, cfg -> cfg.queueCapacity = value);
    }

    private static int capacityLiveLimit(CommandContext<CommandSourceStack> context) {
        int value = IntegerArgumentType.getInteger(context, "value");
        int ceiling = CapacityController.runtime().liveMaxCeiling;
        if (value > ceiling) {
            context.getSource().sendFailure(Component.literal("§c[WorldData] LIVE limit is capped at " + ceiling
                    + " by capacity.liveMaxCeiling in worlddata_core.json."));
            return 0;
        }
        return updateGovernor(context, "LIVE limit set to " + value, cfg -> cfg.liveLimit = value);
    }

    private static int capacityLiveGrace(CommandContext<CommandSourceStack> context) {
        int value = IntegerArgumentType.getInteger(context, "value");
        return updateGovernor(context, "LIVE release grace set to " + value + "s", cfg -> cfg.liveReleaseGraceSeconds = value);
    }

    private static int capacityDeferredMax(CommandContext<CommandSourceStack> context) {
        int value = IntegerArgumentType.getInteger(context, "value");
        return updateGovernor(context, "Deferred maximum set to " + value, cfg -> cfg.deferredMax = value);
    }

    private static int updateCapacity(CommandContext<CommandSourceStack> context, String label, Consumer<CapacityConfig> mutate) {
        CommandSourceStack source = context.getSource();
        WDCCache cache = WDCCacheProvider.get();
        if (cache == null) {
            source.sendFailure(Component.literal("§c[WorldData] Cache not initialized yet."));
            return 0;
        }
        CapacityConfig next = CapacityController.runtime();
        mutate.accept(next);
        try {
            CapacityController.update(next, cache, DelayedSpillPolicyProvider.get());
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal("§c[WorldData] " + invalid.getMessage()));
            return 0;
        }
        send(source, "§a[WorldData] " + label + " (this session).");
        return 1;
    }

    private static int capacityStaleMax(CommandContext<CommandSourceStack> context) {
        int value = IntegerArgumentType.getInteger(context, "value");
        return updateCapacity(context, "STALE maximum set to " + value, cfg -> cfg.staleMax = value);
    }

    private static int capacityEvictionBatch(CommandContext<CommandSourceStack> context) {
        int value = IntegerArgumentType.getInteger(context, "value");
        return updateCapacity(context, "Eviction batch set to " + value, cfg -> cfg.evictionBatch = value);
    }

    private static int capacitySpillQueue(CommandContext<CommandSourceStack> context) {
        int value = IntegerArgumentType.getInteger(context, "value");
        return updateCapacity(context, "Spill IO queue length set to " + value, cfg -> cfg.spillQueueLength = value);
    }

    private static void send(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private static IngestionGovernor requireGovernor(CommandSourceStack source) {
        IngestionGovernor governor = IngestionGovernorProvider.get();
        if (governor == null) source.sendFailure(Component.literal("§c[WorldData] Ingestion governor not initialized yet."));
        return governor;
    }

    private static int performanceStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        IngestionGovernor governor = requireGovernor(source);
        if (governor == null) return 0;

        GovernorMetricsSnapshot snap = governor.metricsSnapshot();
        send(source, "§b=== WorldData Ingestion Governor ===");
        send(source, LoadReport.budgetLine(snap.budget()));
        send(source, budgetLastTickLine(snap.budget()));
        send(source, "§7Queue depth/max observed: §f" + snap.queueDepth() + "§7/§f" + snap.maxQueueDepthObserved());
        send(source, "§7Last drain work units: §f" + snap.lastDrainWorkUnits());
        send(source, "§7Last drain duration (ms): §f" + fmtMs(snap.lastDrainDurationNanos()));

        long enqueued = WDCMetrics.get(WDCMetrics.Counter.GOVERNOR_ENQUEUED);
        long coalesced = WDCMetrics.get(WDCMetrics.Counter.GOVERNOR_COALESCED);
        long dropped = WDCMetrics.get(WDCMetrics.Counter.GOVERNOR_DROPPED);
        long deferred = WDCMetrics.get(WDCMetrics.Counter.GOVERNOR_DEFERRED);
        long completed = WDCMetrics.get(WDCMetrics.Counter.GOVERNOR_COMPLETED);
        long exhausted = WDCMetrics.get(WDCMetrics.Counter.GOVERNOR_BUDGET_EXHAUSTED);

        send(source, "§7Enqueued/coalesced/dropped/deferred/completed: §f" + enqueued
                + "§7/§f" + coalesced + "§7/§c" + dropped + "§7/§e" + deferred + "§7/§a" + completed);
        send(source, "§7Time-slice exhaustions: §e" + exhausted);
        return 1;
    }

    private static int performanceReset(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        IngestionGovernor governor = requireGovernor(source);
        if (governor == null) return 0;
        governor.applyConfig(WDCConfig.get().performance.copy());
        send(source, "§a[WorldData] Ingestion performance overrides reset to configured defaults.");
        return 1;
    }

    private static int printStats(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        WDCCache cache = WDCCacheProvider.get();
        if (cache == null) {
            send(source, "§c[WorldData] Cache not initialized yet.");
            return 0;
        }

        String dim = source.getLevel().dimension().location().toString();
        ChunkRegistry registry = ChunkRegistryProvider.forDimension(dim);

        send(source, "§b=== WorldData Core Diagnostics ===");
        send(source, "§7Dimension: §f" + dim);
        send(source, "§7LIVE Chunks (In RAM): §a" + cache.liveCount(dim));
        send(source, "§7STALE Chunks (Retained): §e" + cache.staleCount(dim));
        send(source, "§7Registry Size: §f" + registry.chunkCount());
        for (String line : LoadReport.coreCostLines()) send(source, line);
        send(source, "§7Shared layers: built §f" + WDCMetrics.get(WDCMetrics.Counter.DERIVED_LAYER_BUILT)
                + " §7reused §f" + WDCMetrics.get(WDCMetrics.Counter.DERIVED_LAYER_REUSED)
                + " §7joined §f" + WDCMetrics.get(WDCMetrics.Counter.DERIVED_LAYER_JOINED));
        send(source, "§7Light reads: hit §a" + WDCMetrics.get(WDCMetrics.Counter.LIGHT_READ_HIT)
                + " §7pending §e" + WDCMetrics.get(WDCMetrics.Counter.LIGHT_READ_PENDING)
                + " §7stale §e" + WDCMetrics.get(WDCMetrics.Counter.LIGHT_READ_STALE)
                + " §7unloaded §e" + WDCMetrics.get(WDCMetrics.Counter.LIGHT_READ_MISSING_SECTION)
                + " §7no-clock §e" + WDCMetrics.get(WDCMetrics.Counter.LIGHT_READ_NO_CLOCK));
        send(source, "§7Light pump: built §f" + WDCMetrics.get(WDCMetrics.Counter.LIGHT_SECTION_BUILT)
                + " §7fast §a" + WDCMetrics.get(WDCMetrics.Counter.LIGHT_BUILD_FAST)
                + " §7slow §e" + WDCMetrics.get(WDCMetrics.Counter.LIGHT_BUILD_SLOW)
                + " §7unloaded §e" + WDCMetrics.get(WDCMetrics.Counter.LIGHT_SECTION_UNLOADED)
                + " §7dropped §c" + WDCMetrics.get(WDCMetrics.Counter.LIGHT_REQUEST_DROPPED)
                + " §7mismatch §c" + WDCMetrics.get(WDCMetrics.Counter.LIGHT_FASTPATH_MISMATCH));
        send(source, "§7Ingest path: raw palette §f" + (net.wdc.core.world.ingestion.PalettedAccess.isAvailable() ? "§aavailable" : "§eunavailable")
                + " §7tokens via §f" + (net.wdc.core.world.ingestion.BlockStateInterner.holderPathSeen() ? "§amixin field" : "§emap")
                + " §7sections raw §f" + WDCMetrics.get(WDCMetrics.Counter.INGEST_SECTIONS_RAW)
                + " §7compatible §e" + WDCMetrics.get(WDCMetrics.Counter.INGEST_SECTIONS_COMPATIBLE));
        send(source, "§7Chunk resync: lost to STALE §e" + WDCMetrics.get(WDCMetrics.Counter.LIVE_RECONCILED)
                + " §7revived §a" + WDCMetrics.get(WDCMetrics.Counter.STALE_REVIVED)
                + " §7offered by sweep or revival §f" + WDCMetrics.get(WDCMetrics.Counter.RESYNC_OFFERED));
        send(source, WDCHotCommands.statsLine());
        send(source, "§7Cache Evictions: §c" + cache.evictionCount());
        for (String line : LoadReport.statusLines(LoadReport.capture())) send(source, line);

        DelayedSpillPolicy spillPolicy = DelayedSpillPolicyProvider.get();
        if (spillPolicy != null) {
            SpillIoStats io = spillPolicy.ioStats();
            send(source, "§b--- Spill IO (Phase 6-0) ---");
            send(source, "§7Spill queued/done/failed: §f" + io.spillQueued() + "§7/§a" + io.spillCompleted() + "§7/§c" + io.spillFailed());
            send(source, "§7Restore queued/done/missed/failed: §f" + io.restoreQueued() + "§7/§a" + io.restoreCompleted() + "§7/§e" + io.restoreMissed() + "§7/§c" + io.restoreFailed());
            if (io.restoreSuppressed() > 0) send(source, "§7Restores skipped (known missing): §e" + io.restoreSuppressed());
            send(source, "§7Discarded (stale/superseded): §e" + (io.spillDiscarded() + io.restoreDiscarded()));
            send(source, "§7IO queue depth/in-flight/capacity: §f" + io.queueDepth() + "§7/§f" + io.inFlight() + "§7/§f" + io.queueCapacity());
            if (io.deferred() > 0) send(source, "§7Deferred (queue full): §e" + io.deferred());
        }

        for (String line : DiagnosticsReport.lines(diagnostics().snapshot())) send(source, line);

        appendOpsStats(source);
        appendPhase6AStats(source);
        appendChangeStats(source);
        appendGovernorStats(source);
        return 1;
    }

    private static void appendChangeStats(CommandSourceStack source) {
        WorldChangeTracker tracker = WorldChangeTrackerProvider.getOrCreate();
        send(source, "§b--- Change feed ---");
        send(source, "§7Subscribers: §f" + tracker.subscriberCount() + " §7Batches: §f" + tracker.batchesDispatched()
                + " §7Events: §f" + WDCMetrics.get(WDCMetrics.Counter.CHANGE_EVENTS_RECORDED)
                + " §7Truncated: §e" + WDCMetrics.get(WDCMetrics.Counter.CHANGE_BATCHES_TRUNCATED));
        send(source, "§7Chunks rebuilt: §a" + WDCMetrics.get(WDCMetrics.Counter.CHANGE_CHUNKS_REBUILT)
                + " §7Rebuild deferred: §e" + WDCMetrics.get(WDCMetrics.Counter.CHANGE_REBUILD_DEFERRED)
                + " §7Listener failures: §c" + WDCMetrics.get(WDCMetrics.Counter.CHANGE_LISTENER_FAILURES));
        send(source, "§7Dirty re-reads: §f" + WDCMetrics.get(WDCMetrics.Counter.CHANGE_DIRTY_REREAD)
                + " §7pending: §f" + WorldChangeTrackerProvider.rescanner().pendingCount());
    }

    private static void appendOpsStats(CommandSourceStack source) {
        for (String line : OpsReport.lines()) send(source, line);
    }

    private static void appendGovernorStats(CommandSourceStack source) {
        IngestionGovernor governor = IngestionGovernorProvider.get();
        if (governor == null) return;
        GovernorMetricsSnapshot snap = governor.metricsSnapshot();
        send(source, "§b--- Ingestion Governor (Phase 6B) ---");
        send(source, LoadReport.budgetLine(snap.budget()) + " §7Queue: §f" + snap.queueDepth() + "/" + snap.maxQueueDepthObserved());
        send(source, budgetLastTickLine(snap.budget()));
        printTimer(source, "Governor drain", WDCMetrics.Timer.GOVERNOR_DRAIN);
    }

    private static void appendPhase6AStats(CommandSourceStack source) {
        send(source, "§b--- Ingestion & Timing (Phase 6A) ---");
        send(source, "§7Chunk ingestions: §f" + WDCMetrics.get(WDCMetrics.Counter.CHUNK_INGESTIONS));
        send(source, "§7Sections skipped/processed: §a" + WDCMetrics.get(WDCMetrics.Counter.EMPTY_SECTIONS_SKIPPED)
                + "§7/§f" + WDCMetrics.get(WDCMetrics.Counter.NON_EMPTY_SECTIONS_PROCESSED));
        send(source, "§7Cache LIVE hits/STALE validations/rebuilds: §a" + WDCMetrics.get(WDCMetrics.Counter.CACHE_LIVE_HITS)
                + "§7/§e" + WDCMetrics.get(WDCMetrics.Counter.CACHE_STALE_VALIDATIONS)
                + "§7/§c" + WDCMetrics.get(WDCMetrics.Counter.CACHE_REBUILDS));

        printTimer(source, "Ingestion", WDCMetrics.Timer.INGESTION);
        printTimer(source, "Section build", WDCMetrics.Timer.SECTION_BUILD);
        printTimer(source, "Fingerprint", WDCMetrics.Timer.FINGERPRINT);
        printTimer(source, "Cache publication", WDCMetrics.Timer.CACHE_PUBLICATION);
        printTimer(source, "Demand processing", WDCMetrics.Timer.DEMAND_PROCESSING);
        printTimer(source, "Scheduler tick", WDCMetrics.Timer.SCHEDULER_PROCESSING);
        printTimer(source, "Spill write", WDCMetrics.Timer.SPILL_WRITE);
        printTimer(source, "Spill prune", WDCMetrics.Timer.SPILL_PRUNE);
        printTimer(source, "Spill read", WDCMetrics.Timer.SPILL_READ);
    }

    private static void printTimer(CommandSourceStack source, String label, WDCMetrics.Timer timer) {
        WDCMetrics.TimerSnapshot snap = WDCMetrics.snapshot(timer);
        if (snap.count() == 0) return;
        send(source, "§7" + label + " avg/p95/p99/max (ms): §f"
                + fmtMs(snap.averageNanos()) + "§7/§f" + fmtMs(snap.p95Nanos())
                + "§7/§f" + fmtMs(snap.p99Nanos()) + "§7/§f" + fmtMs(snap.maxNanos()));
    }

    private static String fmtMs1(double nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    private static String fmtMs(double nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0);
    }

    private static int forceGC(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        send(source, "§a[WorldData] Forcing Garbage Collection...");
        System.gc();
        System.gc();
        send(source, "§a[WorldData] GC Complete. Check F3 memory.");
        return 1;
    }
}
