package net.wdc.core.world.capacity;

import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.storage.ChunkKey;

public interface DemandProbe {

    DemandProbe NONE = (world, chunk) -> false;

    boolean isNeeded(WorldKey world, ChunkKey chunk);

    default DemandProbe snapshot() { return this; }
}
