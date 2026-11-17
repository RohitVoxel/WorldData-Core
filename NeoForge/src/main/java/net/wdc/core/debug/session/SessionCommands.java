package net.wdc.core.debug.session;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.wdc.core.redirect.ReadRedirect;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public final class SessionCommands {

    private SessionCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> subtree(Predicate<CommandSourceStack> admin) {
        return Commands.literal("session").requires(admin)
                .executes(context -> status(context, WDCSession.monitor()))
                .then(Commands.literal("start")
                        .executes(context -> start(context, WDCSession.monitor(), SessionMonitor.DEFAULT_SECONDS))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(SessionMonitor.MIN_SECONDS, SessionMonitor.MAX_SECONDS))
                                .executes(context -> start(context, WDCSession.monitor(),
                                        IntegerArgumentType.getInteger(context, "seconds")))))
                .then(Commands.literal("stop").executes(context -> stop(context, WDCSession.monitor())))
                .then(Commands.literal("status").executes(context -> status(context, WDCSession.monitor())));
    }

    static List<String> statusLines(SessionMonitor.Status status) {
        if (!status.running()) {
            String last = status.lastFile() == null ? "none yet" : status.lastFile().toString();
            return List.of("§7No test session is running. Start one with /worlddata session start [seconds].",
                    "§7Last report: §f" + last + (status.lastSummary() == null ? "" : " §7(" + status.lastSummary() + ")"));
        }
        TickStats.Snapshot ticks = status.ticks();
        String redirect = status.redirectNow() == status.redirectAtStart()
                ? "redirect " + (status.redirectNow() ? "ON" : "OFF")
                : "redirect was " + (status.redirectAtStart() ? "ON" : "OFF") + " at the start and is now "
                + (status.redirectNow() ? "ON" : "OFF");
        return List.of(
                String.format(Locale.ROOT, "§bTest session running: §f%.0f s §7of §f%d s§7, %s", status.elapsedSeconds(),
                        status.requestedSeconds(), redirect),
                "§7" + ticks.summary());
    }

    static List<String> startLines(int seconds, boolean redirectOn, boolean callers, String callerProblem) {
        return List.of(
                "§a[WorldData] Test session started: " + seconds + " s, redirect " + (redirectOn ? "ON" : "OFF")
                        + " at the start. Leave the redirect as it is until it ends.",
                callers ? "§7API callers are being recorded for this session."
                        : "§eAPI callers are not recorded: " + callerProblem,
                "§7Use /worlddata session status or /worlddata session stop. The report is written when it ends.");
    }

    private static void send(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private static int start(CommandContext<CommandSourceStack> context, SessionMonitor monitor, int seconds) {
        CommandSourceStack source = context.getSource();
        SessionMonitor.StartOutcome outcome = monitor.start(seconds, line -> send(source, line));
        switch (outcome) {
            case STARTED -> {
                for (String line : startLines(seconds, ReadRedirect.enabled(), monitor.callerAttribution(),
                        monitor.callerProblem())) {
                    send(source, line);
                }
                return 1;
            }
            case ALREADY_RUNNING -> source.sendFailure(Component.literal(
                    "§c[WorldData] A test session is already running. Use /worlddata session stop first."));
            case NOT_ALLOWED -> source.sendFailure(Component.literal(
                    "§c[WorldData] Test sessions are disabled in worlddata_core.json (session.allowed is false)."));
            case INVALID_DURATION -> source.sendFailure(Component.literal("§c[WorldData] Use "
                    + SessionMonitor.MIN_SECONDS + " to " + SessionMonitor.MAX_SECONDS + " seconds."));
        }
        return 0;
    }

    private static int stop(CommandContext<CommandSourceStack> context, SessionMonitor monitor) {
        CommandSourceStack source = context.getSource();
        if (monitor.stop(line -> send(source, line)) == null) {
            source.sendFailure(Component.literal("§c[WorldData] No test session is running."));
            return 0;
        }
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context, SessionMonitor monitor) {
        for (String line : statusLines(monitor.status())) send(context.getSource(), line);
        return 1;
    }
}
