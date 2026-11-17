package net.wdc.core.api.request;

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

    private static final WDCMaterial[] FROM_INTERNAL = new WDCMaterial[net.wdc.core.world.material.WDCMaterial.values().length];
    private static final net.wdc.core.world.material.WDCMaterial[] TO_INTERNAL =
            new net.wdc.core.world.material.WDCMaterial[values().length];

    static {
        for (net.wdc.core.world.material.WDCMaterial internal : net.wdc.core.world.material.WDCMaterial.values()) {
            for (WDCMaterial api : values()) {
                if (api.name().equals(internal.name())) FROM_INTERNAL[internal.ordinal()] = api;
            }
        }
        for (WDCMaterial api : values()) {
            for (net.wdc.core.world.material.WDCMaterial internal : net.wdc.core.world.material.WDCMaterial.values()) {
                if (api.name().equals(internal.name())) TO_INTERNAL[api.ordinal()] = internal;
            }
        }
    }

    static WDCMaterial from(net.wdc.core.world.material.WDCMaterial internal) {
        if (internal == null) return UNKNOWN;
        WDCMaterial mapped = FROM_INTERNAL[internal.ordinal()];
        return mapped != null ? mapped : valueOf(internal.name());
    }

    net.wdc.core.world.material.WDCMaterial toInternal() {
        net.wdc.core.world.material.WDCMaterial mapped = TO_INTERNAL[ordinal()];
        return mapped != null ? mapped : net.wdc.core.world.material.WDCMaterial.valueOf(name());
    }
}
