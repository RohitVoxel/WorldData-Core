package net.wdc.core.world.cellgrid;

public record CellCoord(int x, int y, int z) {

    public static CellCoord fromWorld(int worldX, int worldY, int worldZ, int cellSize) {
        return new CellCoord(
                Math.floorDiv(worldX, cellSize),
                Math.floorDiv(worldY, cellSize),
                Math.floorDiv(worldZ, cellSize)
        );
    }

    public int centerWorldX(int cellSize) { return x * cellSize + cellSize / 2; }
    public int centerWorldY(int cellSize) { return y * cellSize + cellSize / 2; }
    public int centerWorldZ(int cellSize) { return z * cellSize + cellSize / 2; }
}
