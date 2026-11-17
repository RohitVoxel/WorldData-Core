package net.wdc.core.api.changes;

public record WDCBlockChange(int x, int y, int z, int oldStateId, int newStateId, String oldBlockId, String newBlockId) {

    public static final String AIR = "minecraft:air";
}
