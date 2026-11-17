package net.wdc.core.api.changes;

@FunctionalInterface
public interface WDCChangeListener {

    void onChanges(WDCChangeBatch batch);
}
