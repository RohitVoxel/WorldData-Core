package net.wdc.core.world.request;

import net.wdc.core.world.demand.DataLayer;
import net.wdc.core.world.demand.RetentionPolicy;

import java.util.LinkedHashSet;
import java.util.Set;

public final class WDCRequest {

    private final RequestScope scope;
    private final Set<DataLayer> layers;
    private final RequestPriority priority;
    private final RequestFreshness freshness;
    private final RequestLatencyHint latencyHint;
    private final RetentionPolicy retention;
    private final RequestAccessPattern accessPattern;
    private final Integer lookahead;

    private WDCRequest(Builder b) {
        this.scope = b.scope;
        this.layers = Set.copyOf(b.layers);
        this.priority = b.priority;
        this.freshness = b.freshness;
        this.latencyHint = b.latencyHint;
        this.retention = b.retention;
        this.accessPattern = b.accessPattern;
        this.lookahead = b.lookahead;
    }

    public RequestScope scope() { return scope; }
    public Set<DataLayer> layers() { return layers; }
    public RequestPriority priority() { return priority; }
    public RequestFreshness freshness() { return freshness; }
    public RequestLatencyHint latencyHint() { return latencyHint; }
    public RetentionPolicy retention() { return retention; }
    public RequestAccessPattern accessPattern() { return accessPattern; }

    public Integer lookahead() { return lookahead; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private RequestScope scope;
        private final Set<DataLayer> layers = new LinkedHashSet<>();
        private RequestPriority priority = RequestPriority.NORMAL;
        private RequestFreshness freshness = RequestFreshness.current();
        private RequestLatencyHint latencyHint = RequestLatencyHint.NORMAL;
        private RetentionPolicy retention = RetentionPolicy.AUTO;
        private RequestAccessPattern accessPattern = RequestAccessPattern.UNKNOWN;
        private Integer lookahead;

        private Builder() {}

        public Builder scope(RequestScope scope) {
            this.scope = scope;
            return this;
        }

        public Builder layers(Set<DataLayer> layers) {
            if (layers == null) throw new IllegalArgumentException("layers must not be null");
            this.layers.clear();
            this.layers.addAll(layers);
            return this;
        }

        public Builder layer(DataLayer layer) {
            if (layer == null) throw new IllegalArgumentException("layer must not be null");
            this.layers.add(layer);
            return this;
        }

        public Builder priority(RequestPriority priority) {
            if (priority == null) throw new IllegalArgumentException("priority must not be null");
            this.priority = priority;
            return this;
        }

        public Builder freshness(RequestFreshness freshness) {
            if (freshness == null) throw new IllegalArgumentException("freshness must not be null");
            this.freshness = freshness;
            return this;
        }

        public Builder latencyHint(RequestLatencyHint latencyHint) {
            if (latencyHint == null) throw new IllegalArgumentException("latencyHint must not be null");
            this.latencyHint = latencyHint;
            return this;
        }

        public Builder retention(RetentionPolicy retention) {
            if (retention == null) throw new IllegalArgumentException("retention must not be null");
            this.retention = retention;
            return this;
        }

        public Builder accessPattern(RequestAccessPattern accessPattern) {
            if (accessPattern == null) throw new IllegalArgumentException("accessPattern must not be null");
            this.accessPattern = accessPattern;
            return this;
        }

        public Builder lookahead(int lookahead) {
            if (lookahead < 1) throw new IllegalArgumentException("lookahead must be >= 1");
            this.lookahead = lookahead;
            return this;
        }

        public WDCRequest build() {
            if (scope == null) throw new IllegalStateException("scope must be set");
            if (layers.isEmpty()) throw new IllegalStateException("at least one layer must be requested");
            return new WDCRequest(this);
        }
    }
}
