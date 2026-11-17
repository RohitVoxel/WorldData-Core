package net.wdc.core.api.request;

import java.util.Optional;

public record WDCLightResult(Status status, WDCLightInfo info) {

    public enum Status {
        PRESENT,
        UNLOADED,
        PENDING,
        NO_WORLD,
        UNAVAILABLE
    }

    public static final WDCLightResult UNLOADED = new WDCLightResult(Status.UNLOADED, WDCLightInfo.NONE);
    public static final WDCLightResult PENDING = new WDCLightResult(Status.PENDING, WDCLightInfo.NONE);
    public static final WDCLightResult NO_WORLD = new WDCLightResult(Status.NO_WORLD, WDCLightInfo.NONE);
    public static final WDCLightResult UNAVAILABLE = new WDCLightResult(Status.UNAVAILABLE, WDCLightInfo.NONE);

    public static WDCLightResult present(WDCLightInfo info) {
        if (info == null) throw new IllegalArgumentException("info must not be null");
        return new WDCLightResult(Status.PRESENT, info);
    }

    public boolean isPresent() {
        return status == Status.PRESENT;
    }

    public Optional<WDCLightInfo> toOptional() {
        return status == Status.PRESENT ? Optional.of(info) : Optional.empty();
    }
}
