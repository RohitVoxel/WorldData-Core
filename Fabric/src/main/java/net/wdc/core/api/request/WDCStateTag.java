package net.wdc.core.api.request;

import net.wdc.core.world.storage.StateTags;

public enum WDCStateTag {
    ORE(StateTags.ORE),
    STONE(StateTags.STONE),
    LOG(StateTags.LOG),
    LEAVES(StateTags.LEAVES),
    PLANKS(StateTags.PLANKS),
    FLUID(StateTags.FLUID),
    SAND(StateTags.SAND),
    DIRT(StateTags.DIRT),
    ICE(StateTags.ICE),
    SNOW(StateTags.SNOW),
    GLASS(StateTags.GLASS),
    WOOL(StateTags.WOOL),
    PLANT(StateTags.PLANT),
    CROP(StateTags.CROP),
    MODDED(StateTags.MODDED);

    private final int bit;

    WDCStateTag(int bit) {
        this.bit = bit;
    }

    public int bit() {
        return bit;
    }
}
