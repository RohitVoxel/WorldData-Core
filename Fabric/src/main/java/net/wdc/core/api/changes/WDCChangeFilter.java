package net.wdc.core.api.changes;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public final class WDCChangeFilter {

    private static final WDCChangeFilter ALL = new WDCChangeFilter(Set.of(), EnumSet.noneOf(WDCChangeKind.class), true);

    private final Set<String> dimensions;
    private final Set<WDCChangeKind> kinds;
    private final boolean blockDetails;

    private WDCChangeFilter(Set<String> dimensions, Set<WDCChangeKind> kinds, boolean blockDetails) {
        this.dimensions = Set.copyOf(dimensions);
        this.kinds = kinds.isEmpty() ? Set.of() : Set.copyOf(kinds);
        this.blockDetails = blockDetails;
    }

    public static WDCChangeFilter all() {
        return ALL;
    }

    public WDCChangeFilter forDimensions(String... dimensionIds) {
        return new WDCChangeFilter(new HashSet<>(Arrays.asList(dimensionIds)), kinds, blockDetails);
    }

    public WDCChangeFilter forKinds(WDCChangeKind... changeKinds) {
        Set<WDCChangeKind> next = EnumSet.noneOf(WDCChangeKind.class);
        next.addAll(Arrays.asList(changeKinds));
        return new WDCChangeFilter(dimensions, next, blockDetails);
    }

    public WDCChangeFilter withoutBlockDetails() {
        return new WDCChangeFilter(dimensions, kinds.isEmpty() ? EnumSet.noneOf(WDCChangeKind.class) : EnumSet.copyOf(kinds), false);
    }

    public boolean matchesDimension(String dimensionId) {
        return dimensions.isEmpty() || WDCChangeBatch.ALL_DIMENSIONS.equals(dimensionId) || dimensions.contains(dimensionId);
    }

    public boolean matchesKind(WDCChangeKind kind) {
        return kinds.isEmpty() || kinds.contains(kind);
    }

    public boolean wantsBlockDetails() {
        return blockDetails && matchesKind(WDCChangeKind.BLOCKS_CHANGED);
    }

    public boolean isUnrestricted() {
        return dimensions.isEmpty() && kinds.isEmpty() && blockDetails;
    }
}
