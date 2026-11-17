package net.wdc.core.debug.monitor;

@FunctionalInterface
public interface ModLookup {

    ModLookup NONE = type -> null;

    String modIdOf(Class<?> type);
}
