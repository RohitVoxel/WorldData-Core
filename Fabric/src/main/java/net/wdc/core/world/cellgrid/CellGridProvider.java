package net.wdc.core.world.cellgrid;

public final class CellGridProvider {

    private static volatile CellGrid instance;

    private CellGridProvider() {}

    public static CellGrid getOrCreate() {
        CellGrid local = instance;
        if (local == null) {
            synchronized (CellGridProvider.class) {
                local = instance;
                if (local == null) {
                    local = new CellGrid();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static void reset() {
        instance = null;
    }
}
