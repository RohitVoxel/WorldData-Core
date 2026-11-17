package net.wdc.core.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class CompatibilityManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Compat");

    private final List<CompatibilityModule> registered = new ArrayList<>();
    private final List<CompatibilityModule> active = new ArrayList<>();

    private boolean initialized = false;

    public void register(CompatibilityModule module) {
        if (module == null) {
            throw new IllegalArgumentException("module must not be null");
        }
        registered.add(module);
        if (initialized) {
            tryActivate(module);
        }
    }

    public void initialize() {
        if (initialized) return;
        for (CompatibilityModule module : registered) {
            tryActivate(module);
        }
        initialized = true;
    }

    private void tryActivate(CompatibilityModule module) {
        if (active.contains(module)) return;
        try {
            if (module.isTargetPresent()) {
                module.activate();
                active.add(module);
                LOGGER.info("WorldData: compatibility module active — {}", module.displayName());
            }
        } catch (RuntimeException e) {
            LOGGER.warn("WorldData: compatibility module '{}' failed to activate — {}", module.id(), e.getMessage());
        }
    }

    public void reset() {
        for (CompatibilityModule module : active) {
            try {
                module.reset();
            } catch (RuntimeException e) {
                LOGGER.debug("WorldData: compatibility module '{}' reset failed — {}", module.id(), e.getMessage());
            }
        }
    }

    public List<String> activeModuleNames() {
        List<String> names = new ArrayList<>(active.size());
        for (CompatibilityModule module : active) {
            names.add(module.displayName());
        }
        return List.copyOf(names);
    }

    public int activeModuleCount() {
        return active.size();
    }

    public int registeredModuleCount() {
        return registered.size();
    }

    public CompatibilityDiagnostics diagnostics() {
        return new CompatibilityDiagnostics(initialized, registeredModuleCount(), activeModuleCount(), activeModuleNames());
    }
}
