package net.wdc.core.api.request;

public record WDCBlockInfo(
        boolean present,
        int stateId,
        String blockId,
        WDCMaterial material,
        boolean isFluid,
        int fluidLevel
) {

    public static final WDCBlockInfo ABSENT =
            new WDCBlockInfo(false, -1, null, WDCMaterial.AIR, false, -1);
}
