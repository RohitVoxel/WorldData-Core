package net.wdc.core.api.request;

import net.wdc.core.world.demand.HeightKind;

public enum WDCHeightKind {
    WORLD_SURFACE,
    OCEAN_FLOOR,
    MOTION_BLOCKING,
    MOTION_BLOCKING_NO_LEAVES;

    private static final HeightKind[] INTERNAL = mapInternal();

    private static HeightKind[] mapInternal() {
        WDCHeightKind[] kinds = values();
        HeightKind[] out = new HeightKind[kinds.length];
        for (int i = 0; i < kinds.length; i++) out[i] = HeightKind.valueOf(kinds[i].name());
        return out;
    }

    HeightKind toInternal() {
        return INTERNAL[ordinal()];
    }
}
