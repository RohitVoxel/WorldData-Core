package net.wdc.core.debug.mixin;

import net.wdc.core.config.WDCConfig;
import net.wdc.core.debug.DebugLines;
import net.wdc.core.debug.LoadReport;
import net.wdc.core.debug.OpsReport;
import net.wdc.core.debug.RedirectReport;
import net.wdc.core.debug.ServerTickInfo;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.WDCCacheProvider;
import net.wdc.core.world.storage.ChunkRegistryProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugHudMixin {

    @Inject(method = "getSystemInformation", at = @At("RETURN"), cancellable = true)
    private void wdc$appendF3Stats(CallbackInfoReturnable<List<String>> cir) {
        List<String> lines = cir.getReturnValue();

        if (!WDCConfig.get().debug.showF3Overlay) return;

        WDCCache cache = WDCCacheProvider.get();
        if (cache == null || Minecraft.getInstance().level == null) return;

        String dim = Minecraft.getInstance().level.dimension().location().toString();
        int live = cache.liveCount(dim);
        int stale = cache.staleCount(dim);
        int reg = ChunkRegistryProvider.forDimension(dim).chunkCount();

        lines.add("");
        lines.add("§b[WorldData Core]");
        lines.add("§7Live: §a" + live + " §7| Stale: §e" + stale + " §7| Reg: §f" + reg);
        lines.addAll(LoadReport.coreCostLines());
        lines.addAll(LoadReport.f3Lines(LoadReport.capture()));
        String budgetLine = LoadReport.budgetLine();
        if (budgetLine != null) lines.add(budgetLine);
        lines.add(RedirectReport.f3Line());
        lines.addAll(OpsReport.panelLines());

        Optional<ServerTickInfo.Snapshot> tick = ServerTickInfo.capture();
        if (tick.isPresent()) {
            ServerTickInfo.Snapshot t = tick.get();
            lines.add("§7TPS: §a" + String.format("%.1f", t.tps())
                    + " §7MSPT: §e" + String.format("%.2f", t.mspt())
                    + " §7Budget left: " + (t.budgetRemainingMs() >= 0 ? "§a" : "§c")
                    + String.format("%.2f", t.budgetRemainingMs()) + "ms");
        }

        lines.addAll(DebugLines.collect());

        cir.setReturnValue(lines);
    }
}
