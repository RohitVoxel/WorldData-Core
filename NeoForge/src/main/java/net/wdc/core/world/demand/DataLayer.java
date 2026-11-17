package net.wdc.core.world.demand;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class DataLayer {
    private static final AtomicInteger NEXT_SLOT = new AtomicInteger(0);

    public static final DataLayer BLOCK_STATES = new DataLayer("BLOCK_STATES", Set.of());
    public static final DataLayer HEIGHTMAP = new DataLayer("HEIGHTMAP", Set.of(BLOCK_STATES));
    public static final DataLayer BLOCK_ENTITIES = new DataLayer("BLOCK_ENTITIES", Set.of(BLOCK_STATES));
    public static final DataLayer COLUMN = new DataLayer("COLUMN", Set.of(BLOCK_STATES));
    public static final DataLayer BLOCK_INDEX = new DataLayer("BLOCK_INDEX", Set.of(BLOCK_STATES));
    public static final DataLayer OCCUPANCY = new DataLayer("OCCUPANCY", Set.of(BLOCK_STATES));

    private final String id;
    private final Set<DataLayer> dependencies;
    private final int slotIndex;

    public DataLayer(String id, Set<DataLayer> dependencies) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("id must not be null or empty");
        if (dependencies == null) throw new IllegalArgumentException("dependencies must not be null");
        this.id = id;
        this.dependencies = Collections.unmodifiableSet(new LinkedHashSet<>(dependencies));
        this.slotIndex = NEXT_SLOT.getAndIncrement();
    }

    public String id() { return id; }
    public Set<DataLayer> dependencies() { return dependencies; }

    public int slotIndex() { return slotIndex; }

    @Override public boolean equals(Object o) { return o instanceof DataLayer other && id.equals(other.id); }
    @Override public int hashCode() { return id.hashCode(); }
    @Override public String toString() { return id; }
}
