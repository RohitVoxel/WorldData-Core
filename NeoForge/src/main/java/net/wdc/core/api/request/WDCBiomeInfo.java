package net.wdc.core.api.request;

public record WDCBiomeInfo(
        String biomeId,
        float rawTemperature,
        boolean hasPrecipitation
) {

    public static final WDCBiomeInfo NONE = new WDCBiomeInfo(null, Float.NaN, false);

    public boolean isPresent() {
        return biomeId != null;
    }
}
