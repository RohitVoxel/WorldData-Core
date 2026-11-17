package net.wdc.core.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.wdc.core.config.HotSectionsConfig;
import net.wdc.core.config.WDCConfig;
import net.wdc.core.redirect.ReadRedirect;
import net.wdc.core.redirect.RedirectCounts;
import net.wdc.core.world.hot.HotSections;
import net.wdc.core.world.hot.HotSectionsProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Predicate;

final class WDCHotCommands {

    private static final int LIST_LIMIT = 10;

    private WDCHotCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> pinSubtree(Predicate<CommandSourceStack> admin) {
        return Commands.literal("pin").requires(admin)
                .then(Commands.literal("here").executes(context -> pin(context.getSource(), here(context.getSource()))))
                .then(Commands.literal("area")
                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                .then(Commands.argument("y1", IntegerArgumentType.integer())
                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                        .then(Commands.argument("y2", IntegerArgumentType.integer())
                                                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                        .executes(WDCHotCommands::pinArea))))))))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(context -> pin(context.getSource(), at(context))))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> unpinSubtree(Predicate<CommandSourceStack> admin) {
        return Commands.literal("unpin").requires(admin)
                .then(Commands.literal("here").executes(context -> unpin(context.getSource(), here(context.getSource()))))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(context -> unpin(context.getSource(), at(context))))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> pinsCommand() {
        return Commands.literal("pins").executes(WDCHotCommands::listPins);
    }

    static LiteralArgumentBuilder<CommandSourceStack> redirectSubtree(Predicate<CommandSourceStack> admin) {
        return Commands.literal("redirect").requires(admin)
                .executes(context -> redirectStatus(context.getSource()))
                .then(Commands.literal("on").executes(context -> setRedirect(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> setRedirect(context.getSource(), false)))
                .then(Commands.literal("reset").executes(context -> resetRedirectCounts(context.getSource())));
    }

    static LiteralArgumentBuilder<CommandSourceStack> overlaySubtree(Predicate<CommandSourceStack> admin) {
        return Commands.literal("overlay").requires(admin)
                .executes(context -> overlay(context.getSource(), !HotOverlay.enabled()))
                .then(Commands.literal("on").executes(context -> overlay(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> overlay(context.getSource(), false)))
                .then(Commands.literal("radius")
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(1, HotSectionsConfig.MAX_OVERLAY_RADIUS))
                                .executes(context -> overlayRadius(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "chunks")))));
    }

    static String usageLine(HotSections.Status status) {
        return "§7Hot sections: §f" + status.pinned() + " §7pinned, §f" + status.auto() + " §7auto — §f"
                + status.used() + "/" + status.maxSections() + " §7sections, ≈§f" + megabytes(status.usedBytes())
                + " MB §7of §f" + megabytes(status.maxBytes()) + " MB §7(auto " + (status.autoPromote() ? "§aon" : "§eoff") + "§7)";
    }

    static String statsLine() {
        HotSections hot = HotSectionsProvider.get();
        if (hot == null) return "§7Hot sections: §fnot initialized";
        return usageLine(hot.status());
    }

    static String megabytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.2f", bytes / (1024.0 * 1024.0));
    }

    private record Spot(int x, int y, int z) {}

    private static Spot here(CommandSourceStack source) {
        var position = source.getPosition();
        return new Spot((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z));
    }

    private static Spot at(CommandContext<CommandSourceStack> context) {
        return new Spot(IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"));
    }

    private static HotSections require(CommandSourceStack source) {
        HotSections hot = HotSectionsProvider.get();
        if (hot == null) source.sendFailure(Component.literal("§c[WorldData] Hot sections are not initialized yet."));
        return hot;
    }

    private static String dimension(CommandSourceStack source) {
        return source.getLevel().dimension().location().toString();
    }

    private static String describe(Spot spot) {
        return "section " + (spot.x() >> 4) + ", " + (spot.y() >> 4) + ", " + (spot.z() >> 4);
    }

    private static void send(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private static int pin(CommandSourceStack source, Spot spot) {
        HotSections hot = require(source);
        if (hot == null) return 0;
        String dim = dimension(source);
        HotSections.PinResult result = hot.pin(dim, spot.x(), spot.y(), spot.z());
        switch (result) {
            case PINNED -> {
                boolean loaded = hot.isHot(dim, spot.x() >> 4, spot.y() >> 4, spot.z() >> 4);
                send(source, "§a[WorldData] Pinned " + describe(spot) + " in " + dim
                        + (loaded ? " — hot form active." : " — it becomes hot when its data is in the cache."));
                send(source, usageLine(hot.status()));
                return 1;
            }
            case ALREADY_PINNED -> {
                send(source, "§e[WorldData] " + describe(spot) + " is already pinned.");
                return 0;
            }
            case BUDGET_FULL -> {
                HotSections.Status status = hot.status();
                source.sendFailure(Component.literal("§c[WorldData] Hot budget is full (" + status.used() + "/"
                        + status.maxSections() + " sections, ≈" + megabytes(status.usedBytes())
                        + " MB). Unpin a section or raise hot.maxSections in worlddata_core.json."));
                return 0;
            }
            default -> {
                source.sendFailure(Component.literal("§c[WorldData] Pinning is disabled because hot.maxSections is 0."));
                return 0;
            }
        }
    }

    private static int unpin(CommandSourceStack source, Spot spot) {
        HotSections hot = require(source);
        if (hot == null) return 0;
        if (!hot.unpin(dimension(source), spot.x(), spot.y(), spot.z())) {
            send(source, "§e[WorldData] " + describe(spot) + " is not pinned.");
            return 0;
        }
        send(source, "§a[WorldData] Unpinned " + describe(spot) + ". It now follows the automatic rules.");
        send(source, usageLine(hot.status()));
        return 1;
    }

    private static int pinArea(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        HotSections hot = require(source);
        if (hot == null) return 0;
        HotSections.AreaResult result = hot.pinArea(dimension(source),
                IntegerArgumentType.getInteger(context, "x1"), IntegerArgumentType.getInteger(context, "y1"),
                IntegerArgumentType.getInteger(context, "z1"), IntegerArgumentType.getInteger(context, "x2"),
                IntegerArgumentType.getInteger(context, "y2"), IntegerArgumentType.getInteger(context, "z2"));
        if (result.tooLarge()) {
            source.sendFailure(Component.literal("§c[WorldData] That box touches " + result.sections()
                    + " sections; the limit for one command is " + HotSections.MAX_AREA_SECTIONS + "."));
            return 0;
        }
        send(source, "§a[WorldData] Area touches " + result.sections() + " sections: pinned " + result.pinned()
                + ", already pinned " + result.alreadyPinned() + ", refused " + result.refused()
                + (result.refused() > 0 ? " §c(budget full)" : ""));
        send(source, usageLine(hot.status()));
        return result.pinned();
    }

    private static int listPins(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        HotSections hot = require(source);
        if (hot == null) return 0;
        send(source, usageLine(hot.status()));
        List<HotSections.PinKey> pins = hot.pins();
        if (pins.isEmpty()) {
            send(source, "§7No pinned sections.");
            return 1;
        }
        int shown = Math.min(LIST_LIMIT, pins.size());
        for (int i = 0; i < shown; i++) {
            HotSections.PinKey key = pins.get(i);
            boolean live = hot.isHot(key.dimensionId(), key.chunkX(), key.sectionY(), key.chunkZ());
            send(source, "§7- §f" + key.dimensionId() + " §7section §f" + key.chunkX() + ", " + key.sectionY() + ", "
                    + key.chunkZ() + (live ? " §a[hot]" : " §e[waiting for data]"));
        }
        if (pins.size() > shown) send(source, "§7... and " + (pins.size() - shown) + " more");
        return pins.size();
    }

    private static int redirectStatus(CommandSourceStack source) {
        for (String line : RedirectReport.statusLines(ReadRedirect.enabled(), WDCConfig.get().hot.redirectReads,
                ReadRedirect.counts())) {
            send(source, line);
        }
        return 1;
    }

    private static int setRedirect(CommandSourceStack source, boolean on) {
        RedirectCounts before = ReadRedirect.counts();
        boolean changed = ReadRedirect.set(on, "command");
        send(source, "§a[WorldData] Hot-read redirect " + (on ? "enabled" : "disabled")
                + (changed ? "." : " (it already was)."));
        if (changed) send(source, RedirectReport.finalLine(before));
        return 1;
    }

    private static int resetRedirectCounts(CommandSourceStack source) {
        RedirectCounts before = ReadRedirect.counts();
        ReadRedirect.resetCounters();
        send(source, "§a[WorldData] Redirect counters cleared.");
        send(source, RedirectReport.finalLine(before));
        return 1;
    }

    private static int overlay(CommandSourceStack source, boolean on) {
        if (source.getServer() != null && source.getServer().isDedicatedServer()) {
            send(source, "§e[WorldData] The overlay is drawn by the client. Use the WorldData overlay key on your client;"
                    + " this command only works in singleplayer or on a LAN host.");
            return 0;
        }
        HotOverlay.set(on);
        send(source, "§a[WorldData] Section overlay " + (on ? "on: yellow = normal, green = hot." : "off."));
        return 1;
    }

    private static int overlayRadius(CommandSourceStack source, int chunks) {
        WDCConfig.get().hot.overlayRadiusChunks = chunks;
        WDCConfig.save();
        send(source, "§a[WorldData] Overlay radius set to " + chunks + " chunks.");
        return 1;
    }
}
