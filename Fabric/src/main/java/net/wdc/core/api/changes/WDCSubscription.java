package net.wdc.core.api.changes;

public interface WDCSubscription extends AutoCloseable {

    boolean isActive();

    @Override
    void close();
}
