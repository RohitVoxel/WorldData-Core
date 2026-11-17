package net.wdc.core.api.request;

@FunctionalInterface
public interface BlockVisitor {
    boolean visit(int x, int y, int z, int stateId, String blockId);
}
