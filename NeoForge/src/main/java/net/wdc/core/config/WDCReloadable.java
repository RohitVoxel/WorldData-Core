package net.wdc.core.config;

@FunctionalInterface
public interface WDCReloadable {
    void onConfigReload();
}
