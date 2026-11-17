package net.wdc.core.world.demand;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class ChunkRefCount {
    private final Map<Object, QueryPriority> queries = new HashMap<>();
    private final Map<DataLayer, Integer> layerRequestCount = new HashMap<>();

    synchronized void addQuery(Object queryId, Set<DataLayer> layers, QueryPriority priority) {
        queries.put(queryId, priority);
        for (DataLayer layer : layers) layerRequestCount.merge(layer, 1, Integer::sum);
    }

    synchronized boolean removeQuery(Object queryId, Set<DataLayer> layers) {
        QueryPriority removed = queries.remove(queryId);
        if (removed == null) return queries.isEmpty();
        for (DataLayer layer : layers) {
            layerRequestCount.computeIfPresent(layer, (l, c) -> c <= 1 ? null : c - 1);
        }
        return queries.isEmpty();
    }

    synchronized int interestedQueryCount() { return queries.size(); }

    synchronized QueryPriority highestPriority() {
        QueryPriority best = QueryPriority.LOW;
        for (QueryPriority p : queries.values()) {
            if (p.ordinal() > best.ordinal()) best = p;
        }
        return best;
    }

    synchronized boolean wantsLayer(DataLayer layer) {
        return layerRequestCount.getOrDefault(layer, 0) > 0;
    }
}
