package net.wdc.core.world.material;

public enum WDCMaterial {
    AIR,
    STONE,
    DIRT,
    GRASS,
    SAND,
    GRAVEL,
    CLAY,
    ICE,
    SNOW,
    WATER,
    LAVA,
    LEAVES,
    PLANT,
    CROP,
    WOOD,
    METAL,
    GLASS,
    WOOL,
    UNKNOWN;

    public boolean isFluid() {
        return this == WATER || this == LAVA;
    }

    public boolean isFoliage() {
        return this == LEAVES || this == PLANT || this == CROP;
    }
}
