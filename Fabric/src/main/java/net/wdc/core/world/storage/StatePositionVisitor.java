package net.wdc.core.world.storage;

@FunctionalInterface
public interface StatePositionVisitor {
    void accept(int globalStateId, int packedPosition);
}
