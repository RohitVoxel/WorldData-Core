package net.wdc.core.api.extensions;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class ExtensionRegistryImpl implements ExtensionRegistry {

    private final ConcurrentMap<Class<?>, Object> providers = new ConcurrentHashMap<>();

    @Override
    public <T> Optional<T> register(Class<T> type, T provider) {
        if (type == null || provider == null) throw new IllegalArgumentException("type and provider must not be null");
        return Optional.ofNullable(type.cast(providers.put(type, provider)));
    }

    @Override
    public <T> Optional<T> unregister(Class<T> type) {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        return Optional.ofNullable(type.cast(providers.remove(type)));
    }

    @Override
    public <T> Optional<T> get(Class<T> type) {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        return Optional.ofNullable(type.cast(providers.get(type)));
    }

    @Override
    public boolean isRegistered(Class<?> type) {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        return providers.containsKey(type);
    }

    @Override
    public void clear() {
        providers.clear();
    }
}
