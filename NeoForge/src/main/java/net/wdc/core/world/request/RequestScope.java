package net.wdc.core.world.request;

import net.wdc.core.world.demand.QueryScope;
import net.wdc.core.world.storage.ChunkKey;

import java.util.LinkedHashSet;
import java.util.Set;

public final class RequestScope {

    public enum Kind { RECTANGLE, EXPLICIT_SET }

    private final Kind kind;
    private final QueryScope rectangle;
    private final String dimensionId;
    private final Set<ChunkKey> explicitChunks;

    private RequestScope(Kind kind, QueryScope rectangle, String dimensionId, Set<ChunkKey> explicitChunks) {
        this.kind = kind;
        this.rectangle = rectangle;
        this.dimensionId = dimensionId;
        this.explicitChunks = explicitChunks;
    }

    public static RequestScope rectangle(String dimensionId, int chunkX1, int chunkZ1, int chunkX2, int chunkZ2) {
        return new RequestScope(Kind.RECTANGLE,
                QueryScope.rectangle(dimensionId, chunkX1, chunkZ1, chunkX2, chunkZ2), dimensionId, null);
    }

    public static RequestScope rectangle(QueryScope scope) {
        if (scope == null) throw new IllegalArgumentException("scope must not be null");
        return new RequestScope(Kind.RECTANGLE, scope, scope.dimensionId(), null);
    }

    public static RequestScope single(String dimensionId, int chunkX, int chunkZ) {
        return new RequestScope(Kind.RECTANGLE, QueryScope.single(dimensionId, chunkX, chunkZ), dimensionId, null);
    }

    public static RequestScope explicit(String dimensionId, Set<ChunkKey> chunks) {
        if (dimensionId == null || dimensionId.isEmpty()) throw new IllegalArgumentException("dimensionId must not be null or empty");
        if (chunks == null || chunks.isEmpty()) throw new IllegalArgumentException("chunks must not be null or empty");
        if (chunks.size() > QueryScope.MAX_CHUNK_COUNT) {
            throw new IllegalArgumentException("explicit set has " + chunks.size() + " chunks, exceeding the max of "
                    + QueryScope.MAX_CHUNK_COUNT + " for a single scope; split it into smaller requests");
        }
        return new RequestScope(Kind.EXPLICIT_SET, null, dimensionId, Set.copyOf(new LinkedHashSet<>(chunks)));
    }

    public Kind kind() { return kind; }
    public String dimensionId() { return dimensionId; }

    public QueryScope rectangle() { return rectangle; }

    public Set<ChunkKey> explicitChunks() { return explicitChunks; }

    public long chunkCount() {
        return kind == Kind.RECTANGLE ? rectangle.chunkCount() : explicitChunks.size();
    }

    public boolean contains(int chunkX, int chunkZ) {
        return kind == Kind.RECTANGLE
                ? rectangle.contains(chunkX, chunkZ)
                : explicitChunks.contains(new ChunkKey(chunkX, chunkZ));
    }
}
