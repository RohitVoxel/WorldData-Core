package net.wdc.core.api.spatial;

import java.util.List;

public final class WDCGridSample {

    private final WDCCoverage coverage;
    private final int cellSize;
    private final int originX;
    private final int originZ;
    private final int cellsX;
    private final int cellsZ;
    private final List<WDCGridCell> cells;

    WDCGridSample(WDCCoverage coverage, int cellSize, int originX, int originZ, int cellsX, int cellsZ, List<WDCGridCell> cells) {
        this.coverage = coverage;
        this.cellSize = cellSize;
        this.originX = originX;
        this.originZ = originZ;
        this.cellsX = cellsX;
        this.cellsZ = cellsZ;
        this.cells = List.copyOf(cells);
    }

    public WDCCoverage coverage() {
        return coverage;
    }

    public int cellSize() {
        return cellSize;
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    public int cellsX() {
        return cellsX;
    }

    public int cellsZ() {
        return cellsZ;
    }

    public List<WDCGridCell> cells() {
        return cells;
    }

    public WDCGridCell cell(int indexX, int indexZ) {
        if (indexX < 0 || indexX >= cellsX || indexZ < 0 || indexZ >= cellsZ) {
            throw new IndexOutOfBoundsException("cell " + indexX + "," + indexZ + " outside " + cellsX + "x" + cellsZ);
        }
        return cells.get(indexZ * cellsX + indexX);
    }
}
