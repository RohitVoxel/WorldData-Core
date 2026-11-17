package net.wdc.core.api.spatial;

import net.wdc.core.api.request.WDCMaterial;

public final class WDCRegionSummary {

    private final WDCCoverage coverage;
    private final long sampledVolume;
    private final long[] counts;

    WDCRegionSummary(WDCCoverage coverage, long sampledVolume, long[] counts) {
        this.coverage = coverage;
        this.sampledVolume = sampledVolume;
        this.counts = counts.clone();
    }

    public WDCCoverage coverage() {
        return coverage;
    }

    public long sampledVolume() {
        return sampledVolume;
    }

    public long count(WDCMaterial material) {
        if (material == null) throw new IllegalArgumentException("material must not be null");
        return counts[material.ordinal()];
    }

    public long nonAirCount() {
        return Math.max(0L, sampledVolume - counts[WDCMaterial.AIR.ordinal()]);
    }

    public double fraction(WDCMaterial material) {
        if (sampledVolume == 0L) return 0.0;
        return count(material) / (double) sampledVolume;
    }

    public boolean contains(WDCMaterial material) {
        return count(material) > 0L;
    }
}
