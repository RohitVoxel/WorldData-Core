package net.wdc.core.world.cache;

import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.WDCSection;

public final class DefaultFingerprintSupport {

    private DefaultFingerprintSupport() {}

    public static SectionFingerprint sectionFingerprintOf(WDCSection section, int tickingBlockCount, int tickingFluidCount) {
        if (section == null) return null;

        var counts = section.stateCounts();
        if (counts.isEmpty()) {
            return SectionFingerprint.empty(section.sectionY());
        }

        int nonEmptyBlockCount = 0;
        for (WDCSection.StateCount sc : counts) nonEmptyBlockCount += sc.count();

        return new SectionFingerprint(
                section.sectionY(),
                false,
                counts.size(),
                nonEmptyBlockCount,
                section.contentHash(),
                tickingBlockCount,
                tickingFluidCount
        );
    }

    public static void populateSections(ChunkFingerprint.Builder builder, WDCChunk chunk, TickingCountLookup tickingLookup) {
        long start = WDCMetrics.startNanos();
        for (WDCSection section : chunk.sections()) {
            int[] ticking = tickingLookup.tickingCountsFor(section.sectionY());
            SectionFingerprint sf = sectionFingerprintOf(section, ticking[0], ticking[1]);
            builder.putSection(sf);
        }
        WDCMetrics.record(WDCMetrics.Timer.FINGERPRINT, System.nanoTime() - start);
    }

    @FunctionalInterface
    public interface TickingCountLookup {
        int[] tickingCountsFor(int sectionY);

        TickingCountLookup UNAVAILABLE = sectionY -> new int[] {0, 0};
    }
}
