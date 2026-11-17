package net.wdc.core.api.request;

public record WDCWaterInfo(
        boolean isWater,
        boolean isLava,
        boolean isFluid,
        boolean isSource,
        boolean isFlowing,
        int fluidLevel,
        boolean isFalling
) {

    public static final WDCWaterInfo NONE = new WDCWaterInfo(false, false, false, false, false, -1, false);
}
