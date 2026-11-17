package net.wdc.core.api.spatial;

public record WDCCoverage(int chunks, int live, int stale, int acquiring, int failed) {

    public static final WDCCoverage EMPTY = new WDCCoverage(0, 0, 0, 0, 0);

    public int usable() {
        return live + stale;
    }

    public int unavailable() {
        return chunks - usable();
    }

    public boolean complete() {
        return chunks > 0 && usable() == chunks;
    }

    public boolean fresh() {
        return complete() && stale == 0;
    }
}
