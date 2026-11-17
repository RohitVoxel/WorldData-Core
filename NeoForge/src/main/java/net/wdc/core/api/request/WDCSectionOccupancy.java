package net.wdc.core.api.request;

import net.wdc.core.world.demand.ChunkOccupancy;

public final class WDCSectionOccupancy {

    private final ChunkOccupancy internal;

    WDCSectionOccupancy(ChunkOccupancy internal) {
        if (internal == null) throw new IllegalArgumentException("internal must not be null");
        this.internal = internal;
    }

    public boolean contains(int sectionY, WDCMaterial material) {
        return internal.contains(sectionY, material.toInternal());
    }

    public int countOf(int sectionY, WDCMaterial material) {
        return internal.countOf(sectionY, material.toInternal());
    }

    public boolean isEmpty(int sectionY) {
        return internal.isEmpty(sectionY);
    }
}
