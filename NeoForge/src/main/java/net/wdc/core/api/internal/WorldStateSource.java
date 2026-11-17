package net.wdc.core.api.internal;

import net.wdc.core.api.world.WDCWorldSnapshot;

import java.util.Optional;

public interface WorldStateSource {

    WorldStateSource UNAVAILABLE = dimensionId -> Optional.empty();

    Optional<WDCWorldSnapshot> snapshot(String dimensionId);
}
