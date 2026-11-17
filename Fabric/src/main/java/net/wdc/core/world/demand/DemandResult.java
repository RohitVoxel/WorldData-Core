package net.wdc.core.world.demand;

import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.ChunkKey;

import java.util.Map;

public record DemandResult(ChunkKey chunk, Map<DataLayer, LayerResult<?>> layers) {

    public boolean allLive() {
        if (layers.isEmpty()) return false;
        for (LayerResult<?> lr : layers.values()) {
            if (lr.freshness() != DemandFreshness.LIVE) return false;
        }
        return true;
    }

    public LayerResult<?> layer(DataLayer layer) {
        return layers.get(layer);
    }

    public WDCChunk blockStates() {
        LayerResult<?> bs = layers.get(DataLayer.BLOCK_STATES);
        return bs != null && bs.value() instanceof WDCChunk c ? c : null;
    }
}
