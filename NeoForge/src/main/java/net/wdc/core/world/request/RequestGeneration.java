package net.wdc.core.world.request;

public record RequestGeneration(long value) {

    @Override
    public String toString() { return "Generation#" + value; }
}
