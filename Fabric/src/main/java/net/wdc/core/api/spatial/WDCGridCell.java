package net.wdc.core.api.spatial;

public record WDCGridCell(
        int minX,
        int minZ,
        int size,
        int sampledColumns,
        int solidColumns,
        double averageSolidY,
        int minSolidY,
        int maxSolidY,
        double waterFraction
) {

    public boolean known() {
        return sampledColumns > 0;
    }
}
