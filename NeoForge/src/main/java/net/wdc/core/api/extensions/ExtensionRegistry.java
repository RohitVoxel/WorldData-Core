package net.wdc.core.api.extensions;

import java.util.Optional;

public interface ExtensionRegistry {

    <T> Optional<T> register(Class<T> type, T provider);

    <T> Optional<T> unregister(Class<T> type);

    <T> Optional<T> get(Class<T> type);

    boolean isRegistered(Class<?> type);

    void clear();
}
