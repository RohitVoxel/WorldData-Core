package net.wdc.core.config;

public final class HotSectionsConfig {

    public static final int MAX_SECTIONS_LIMIT = 1024;
    public static final int DEFAULT_MAX_SECTIONS = 192;
    public static final int MAX_OVERLAY_RADIUS = 8;

    public boolean redirectReads = false;
    public boolean autoPromote = true;
    public int maxSections = DEFAULT_MAX_SECTIONS;
    public int promoteAfterTicks = 100;
    public int demoteAfterTicks = 200;
    public int promoteReadsPerTick = 64;
    public int maxMutationsPerWindow = 2;
    public int overlayRadiusChunks = 3;

    public void validate() {
        if (maxSections < 0 || maxSections > MAX_SECTIONS_LIMIT) {
            throw new IllegalArgumentException("maxSections must be within 0.." + MAX_SECTIONS_LIMIT);
        }
        if (promoteAfterTicks < 20 || promoteAfterTicks > 12_000) {
            throw new IllegalArgumentException("promoteAfterTicks must be within 20..12000");
        }
        if (demoteAfterTicks < 20 || demoteAfterTicks > 72_000) {
            throw new IllegalArgumentException("demoteAfterTicks must be within 20..72000");
        }
        if (promoteReadsPerTick < 1 || promoteReadsPerTick > 1_000_000) {
            throw new IllegalArgumentException("promoteReadsPerTick must be within 1..1000000");
        }
        if (maxMutationsPerWindow < 0 || maxMutationsPerWindow > 4096) {
            throw new IllegalArgumentException("maxMutationsPerWindow must be within 0..4096");
        }
        if (overlayRadiusChunks < 1 || overlayRadiusChunks > MAX_OVERLAY_RADIUS) {
            throw new IllegalArgumentException("overlayRadiusChunks must be within 1.." + MAX_OVERLAY_RADIUS);
        }
    }

    public boolean clamp() {
        boolean changed = false;
        int value = Math.max(0, Math.min(MAX_SECTIONS_LIMIT, maxSections));
        if (value != maxSections) { maxSections = value; changed = true; }
        value = Math.max(20, Math.min(12_000, promoteAfterTicks));
        if (value != promoteAfterTicks) { promoteAfterTicks = value; changed = true; }
        value = Math.max(20, Math.min(72_000, demoteAfterTicks));
        if (value != demoteAfterTicks) { demoteAfterTicks = value; changed = true; }
        value = Math.max(1, Math.min(1_000_000, promoteReadsPerTick));
        if (value != promoteReadsPerTick) { promoteReadsPerTick = value; changed = true; }
        value = Math.max(0, Math.min(4096, maxMutationsPerWindow));
        if (value != maxMutationsPerWindow) { maxMutationsPerWindow = value; changed = true; }
        value = Math.max(1, Math.min(MAX_OVERLAY_RADIUS, overlayRadiusChunks));
        if (value != overlayRadiusChunks) { overlayRadiusChunks = value; changed = true; }
        return changed;
    }

    public HotSectionsConfig copy() {
        HotSectionsConfig copy = new HotSectionsConfig();
        copy.redirectReads = redirectReads;
        copy.autoPromote = autoPromote;
        copy.maxSections = maxSections;
        copy.promoteAfterTicks = promoteAfterTicks;
        copy.demoteAfterTicks = demoteAfterTicks;
        copy.promoteReadsPerTick = promoteReadsPerTick;
        copy.maxMutationsPerWindow = maxMutationsPerWindow;
        copy.overlayRadiusChunks = overlayRadiusChunks;
        return copy;
    }
}
