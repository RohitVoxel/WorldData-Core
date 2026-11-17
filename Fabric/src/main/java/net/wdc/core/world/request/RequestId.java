package net.wdc.core.world.request;

public record RequestId(long value) {

    @Override
    public String toString() { return "Request#" + value; }
}
