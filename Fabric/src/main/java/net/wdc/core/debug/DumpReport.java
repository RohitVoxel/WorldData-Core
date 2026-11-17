package net.wdc.core.debug;

import net.wdc.core.debug.diagnostics.WDCChunkDump;
import net.wdc.core.debug.diagnostics.WDCPaletteEntry;
import net.wdc.core.debug.diagnostics.WDCSectionDump;

import java.util.ArrayList;
import java.util.List;

public final class DumpReport {

    public static final int MAX_PALETTE_LINES = 12;

    private DumpReport() {}

    public static List<String> chunkLines(WDCChunkDump dump) {
        List<String> lines = new ArrayList<>();
        lines.add("§b=== Chunk " + dump.chunkX() + ", " + dump.chunkZ() + " (" + dump.dimensionId() + ") ===");
        lines.add("§7Freshness: §f" + dump.freshness() + " §7version §f" + dump.version()
                + " §7memory §f" + DiagnosticsReport.bytes(dump.estimatedBytes()) + " §7sections §f" + dump.sections().size());
        lines.add("§7Biome: §f" + (dump.biomePresent() ? dump.biomeId() : "not recorded"));
        for (WDCSectionDump section : dump.sections()) {
            lines.add("§7Section §f" + section.sectionY() + " §7" + section.storageMode() + " occupied §f"
                    + section.occupied() + " §7palette §f" + section.paletteSize() + " §7v§f" + section.version());
        }
        return lines;
    }

    public static List<String> sectionLines(int chunkX, int chunkZ, WDCSectionDump section) {
        List<String> lines = new ArrayList<>();
        lines.add("§b=== Section " + chunkX + ", " + section.sectionY() + ", " + chunkZ + " ===");
        lines.add("§7Mode: §f" + section.storageMode() + " §7occupied §f" + section.occupied() + "/4096 §7version §f"
                + section.version() + " §7memory §f" + DiagnosticsReport.bytes(section.estimatedBytes()));
        List<WDCPaletteEntry> palette = section.palette();
        int shown = Math.min(MAX_PALETTE_LINES, palette.size());
        for (int i = 0; i < shown; i++) {
            WDCPaletteEntry entry = palette.get(i);
            lines.add("§7  #" + entry.stateId() + " §f" + entry.state() + " §7x§f" + entry.count());
        }
        if (palette.size() > shown) lines.add("§7  ... " + (palette.size() - shown) + " more states");
        return lines;
    }
}
