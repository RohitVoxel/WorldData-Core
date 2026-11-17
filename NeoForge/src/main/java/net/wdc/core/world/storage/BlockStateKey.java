package net.wdc.core.world.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public final class BlockStateKey {

    private final String blockId;
    private final SortedMap<String, String> properties;

    private final int hash;
    private volatile String asString;

    public BlockStateKey(String blockId, Map<String, String> properties) {
        if (blockId == null || blockId.isEmpty()) {
            throw new IllegalArgumentException("blockId must not be null or empty");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }

        this.blockId = blockId;
        this.properties = properties.isEmpty()
                ? java.util.Collections.emptySortedMap()
                : java.util.Collections.unmodifiableSortedMap(new TreeMap<>(properties));
        this.hash = this.blockId.hashCode() * 31 + this.properties.hashCode();
    }

    public static BlockStateKey of(String blockId) {
        return new BlockStateKey(blockId, Map.of());
    }

    public static BlockStateKey of(String blockId, Map<String, String> properties) {
        return new BlockStateKey(blockId, properties);
    }

    public String blockId() {
        return blockId;
    }

    public SortedMap<String, String> properties() {
        return properties;
    }

    public boolean hasProperties() {
        return !properties.isEmpty();
    }

    public String asString() {
        String cached = asString;
        if (cached != null) return cached;

        String built;
        if (properties.isEmpty()) {
            built = blockId;
        } else {
            StringBuilder sb = new StringBuilder(blockId).append('[');
            boolean first = true;
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                if (!first) sb.append(',');
                sb.append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
            sb.append(']');
            built = sb.toString();
        }
        asString = built;
        return built;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockStateKey other)) return false;
        return hash == other.hash && blockId.equals(other.blockId) && properties.equals(other.properties);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return asString();
    }

    public static final class Builder {
        private final String blockId;
        private final Map<String, String> properties = new LinkedHashMap<>();

        public Builder(String blockId) {
            this.blockId = blockId;
        }

        public Builder property(String key, String value) {
            properties.put(key, value);
            return this;
        }

        public BlockStateKey build() {
            return new BlockStateKey(blockId, properties);
        }
    }
}
