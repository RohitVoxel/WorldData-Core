package net.wdc.core.world.storage;

import net.wdc.core.world.material.WDCMaterial;

public final class StateTags {

    public static final int ORE = 1;
    public static final int STONE = 1 << 1;
    public static final int LOG = 1 << 2;
    public static final int LEAVES = 1 << 3;
    public static final int PLANKS = 1 << 4;
    public static final int FLUID = 1 << 5;
    public static final int SAND = 1 << 6;
    public static final int DIRT = 1 << 7;
    public static final int ICE = 1 << 8;
    public static final int SNOW = 1 << 9;
    public static final int GLASS = 1 << 10;
    public static final int WOOL = 1 << 11;
    public static final int PLANT = 1 << 12;
    public static final int CROP = 1 << 13;
    public static final int MODDED = 1 << 14;
    public static final int BLOCKS_MOTION = 1 << 15;
    public static final int MASK = 0xFFFF;

    private StateTags() {}

    public static int fromMaterial(WDCMaterial material) {
        if (material == null) return 0;
        return switch (material) {
            case STONE -> STONE;
            case DIRT, GRASS -> DIRT;
            case SAND, GRAVEL -> SAND;
            case ICE -> ICE;
            case SNOW -> SNOW;
            case WATER, LAVA -> FLUID;
            case LEAVES -> LEAVES;
            case PLANT -> PLANT;
            case CROP -> CROP;
            case GLASS -> GLASS;
            case WOOL -> WOOL;
            default -> 0;
        };
    }

    public static boolean blocksMotion(String blockId, WDCMaterial material) {
        if (!blocksMotion(material)) return false;
        if (material == WDCMaterial.GRASS && blockId != null) {
            return !(blockId.endsWith(":short_grass") || blockId.endsWith(":tall_grass")
                    || blockId.endsWith(":fern") || blockId.endsWith(":large_fern"));
        }
        return true;
    }

    public static boolean blocksMotion(WDCMaterial material) {
        if (material == null) return false;
        return switch (material) {
            case AIR, WATER, LAVA, PLANT, CROP -> false;
            default -> true;
        };
    }
}
