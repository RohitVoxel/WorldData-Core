package net.wdc.core.world.coalesce;

import net.wdc.core.world.demand.DataLayer;
import net.wdc.core.world.demand.LayerResult;
import net.wdc.core.world.request.RequestGeneration;
import net.wdc.core.world.storage.ChunkKey;

import java.util.Map;

public record ChunkResult(
        ChunkKey chunk,
        RequestGeneration generation,
        Map<DataLayer, LayerResult<?>> availableLayers,
        boolean complete) {

    public ChunkResult {
        if (chunk == null || generation == null || availableLayers == null) {
            throw new IllegalArgumentException("chunk/generation/availableLayers must not be null");
        }
        availableLayers = Map.copyOf(availableLayers);
    }

    public LayerResult<?> layer(DataLayer layer) { return availableLayers.get(layer); }
}
