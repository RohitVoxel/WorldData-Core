package net.wdc.core.api.request;

public record WDCLightInfo(
        int skyLight,
        int blockLight,
        int skyDarkening,
        int effectiveSkyLight,
        int combinedLight
) {

    public static final WDCLightInfo NONE = new WDCLightInfo(-1, -1, 0, -1, -1);

    public boolean isPresent() {
        return skyLight >= 0 && blockLight >= 0;
    }
}
