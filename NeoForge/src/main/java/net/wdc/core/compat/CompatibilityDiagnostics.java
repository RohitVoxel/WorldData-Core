package net.wdc.core.compat;

import java.util.List;

public record CompatibilityDiagnostics(
        boolean initialized,
        int registeredModuleCount,
        int activeModuleCount,
        List<String> activeModuleNames
) {}
