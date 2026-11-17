package net.wdc.core.compat;

public interface CompatibilityModule {

    String id();

    String displayName();

    boolean isTargetPresent();

    void activate();

    void publish(CompatibilitySnapshot snapshot);

    void reset();
}
