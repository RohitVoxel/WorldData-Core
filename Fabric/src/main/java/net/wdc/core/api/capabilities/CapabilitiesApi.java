package net.wdc.core.api.capabilities;

import java.util.EnumSet;
import java.util.Set;

public final class CapabilitiesApi {

    private final Set<WDCCapability> available;

    public CapabilitiesApi(Set<WDCCapability> available) {
        this.available = available == null || available.isEmpty()
                ? EnumSet.noneOf(WDCCapability.class)
                : EnumSet.copyOf(available);
    }

    public boolean supports(WDCCapability capability) {
        return available.contains(capability);
    }

    public Set<WDCCapability> all() {
        return Set.copyOf(available);
    }
}
