package net.wdc.core.api;

import net.minecraft.resources.ResourceLocation;

public final class WDCTickPhase {

    public static final ResourceLocation BEGIN = ResourceLocation.fromNamespaceAndPath("worlddata_core", "tick_begin");
    public static final ResourceLocation DRAIN = ResourceLocation.fromNamespaceAndPath("worlddata_core", "tick_drain");

    private WDCTickPhase() {}
}
