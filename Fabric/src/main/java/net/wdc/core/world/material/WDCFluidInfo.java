package net.wdc.core.world.material;

public record WDCFluidInfo(Kind kind, boolean source, int amount, boolean falling) {

    public enum Kind { NONE, WATER, LAVA, OTHER }

    public static final WDCFluidInfo NONE = new WDCFluidInfo(Kind.NONE, false, 0, false);
    public static final WDCFluidInfo UNKNOWN_FLUID = new WDCFluidInfo(Kind.OTHER, false, 0, false);

    public boolean present() {
        return kind != Kind.NONE;
    }

    public int level() {
        if (!present()) return -1;
        if (source) return 0;
        if (amount <= 0) return -1;
        return Math.max(1, Math.min(7, 8 - amount));
    }
}
