package net.wdc.core.world.demand;

import net.wdc.core.world.storage.WDCChunk;

@FunctionalInterface
public interface LayerBuilder<T> {
    T build(WDCChunk chunk);
}
