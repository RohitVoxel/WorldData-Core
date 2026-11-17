package net.wdc.core.debug.monitor;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.wdc.core.config.CorePaths;
import net.wdc.core.config.MonitorConfig;
import net.wdc.core.config.WDCConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Predicate;

public final class MonitorCommands {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private MonitorCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> subtree(Predicate<CommandSourceStack> admin) {
        return Commands.literal("monitor")
                .executes(context -> report(context, MonitorReport.DEFAULT_TOP))
                .then(Commands.literal("report")
                        .executes(context -> report(context, MonitorReport.DEFAULT_TOP))
                        .then(Commands.argument("top", IntegerArgumentType.integer(1, MonitorReport.MAX_TOP))
                                .executes(context -> report(context, IntegerArgumentType.getInteger(context, "top")))))
                .then(Commands.literal("on").requires(admin).executes(MonitorCommands::on))
                .then(Commands.literal("off").requires(admin).executes(MonitorCommands::off))
                .then(Commands.literal("reset").requires(admin).executes(MonitorCommands::reset))
                .then(Commands.literal("save").requires(admin).executes(MonitorCommands::save))
                .then(Commands.literal("sample").requires(admin)
                        .then(Commands.argument("every", IntegerArgumentType.integer(MonitorConfig.MIN_SAMPLE_EVERY, MonitorConfig.MAX_SAMPLE_EVERY))
                                .executes(context -> sample(context, IntegerArgumentType.getInteger(context, "every")))));
    }

    private static void send(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private static int report(CommandContext<CommandSourceStack> context, int top) {
        CommandSourceStack source = context.getSource();
        List<String> lines = MonitorReport.lines(WDCApiMonitor.snapshot(top), top);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean heading = i == 0 || line.startsWith("Top ");
            send(source, (heading ? "§b" : "§7") + line);
        }
        return 1;
    }

    private static int on(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!WDCApiMonitor.enable()) {
            source.sendFailure(Component.literal("§c[WorldData] The API monitor is disabled in worlddata_core.json (monitor.allowed is false)."));
            return 0;
        }
        send(source, "§a[WorldData] API monitor on (callers sampled 1 in " + WDCApiMonitor.sampleEvery()
                + "). Use /worlddata monitor report to read it and /worlddata monitor off to stop it.");
        return 1;
    }

    private static int off(CommandContext<CommandSourceStack> context) {
        WDCApiMonitor.disable();
        send(context.getSource(), "§a[WorldData] API monitor off. The numbers are kept until /worlddata monitor reset.");
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        WDCApiMonitor.reset();
        send(context.getSource(), "§a[WorldData] API monitor counters cleared.");
        return 1;
    }

    private static int sample(CommandContext<CommandSourceStack> context, int every) {
        WDCApiMonitor.setSampleEvery(every);
        WDCConfig.get().monitor.sampleEvery = WDCApiMonitor.sampleEvery();
        WDCConfig.save();
        send(context.getSource(), "§a[WorldData] API monitor now attributes callers on 1 in " + WDCApiMonitor.sampleEvery()
                + " calls (saved to worlddata_core.json). 1 means every call.");
        return 1;
    }

    private static int save(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> lines = MonitorReport.lines(WDCApiMonitor.snapshot(MonitorReport.MAX_TOP), MonitorReport.MAX_TOP);
        try {
            Path directory = CorePaths.root().resolve("monitor");
            Files.createDirectories(directory);
            Path file = directory.resolve("api-usage-" + LocalDateTime.now().format(STAMP) + ".txt");
            Files.write(file, lines, StandardCharsets.UTF_8);
            send(source, "§a[WorldData] API monitor report written to " + file);
            return 1;
        } catch (IOException | RuntimeException failure) {
            source.sendFailure(Component.literal("§c[WorldData] Could not write the report: " + failure.getMessage()));
            return 0;
        }
    }
}
