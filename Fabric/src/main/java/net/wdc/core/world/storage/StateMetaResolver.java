package net.wdc.core.world.storage;

@FunctionalInterface
public interface StateMetaResolver {

    StateMeta resolve(BlockStateKey key);
}
