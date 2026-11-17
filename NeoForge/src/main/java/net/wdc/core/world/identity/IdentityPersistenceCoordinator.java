package net.wdc.core.world.identity;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;

public final class IdentityPersistenceCoordinator {

    private final BlockIdentityStore store;
    private final IdentityPersistenceService service;

    private String pendingLoadDimension = null;

    public IdentityPersistenceCoordinator(BlockIdentityStore store, IdentityPersistenceService service) {
        this.store = store;
        this.service = service;
    }

    public void requestLoad(String dimensionKey) {
        pendingLoadDimension = dimensionKey;
        service.requestLoad(dimensionKey);
    }

    public void drainLoad() {
        if (pendingLoadDimension == null) return;

        Optional<List<IdentityPositionSnapshot>> loaded = service.pollLoadedResult(pendingLoadDimension);
        if (loaded.isEmpty()) return;

        restore(loaded.get());
        pendingLoadDimension = null;
    }

    public void save(String dimensionKey) {
        if (dimensionKey == null) return;
        service.save(dimensionKey, store.exportAll());
    }

    public void saveBlocking(String dimensionKey) {
        if (dimensionKey == null) return;
        service.saveBlocking(dimensionKey, store.exportAll());
    }

    public void cancelPendingLoad() {
        pendingLoadDimension = null;
    }

    private void restore(List<IdentityPositionSnapshot> positions) {
        for (IdentityPositionSnapshot entry : positions) {
            BlockPos pos = entry.pos();
            if (store.has(pos)) continue;
            store.set(pos, entry.seed());
        }
    }
}
