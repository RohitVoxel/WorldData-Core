package net.wdc.core.api.changes;

public interface WDCChangeEntrypoint extends WDCChangeListener {

    String ENTRYPOINT_KEY = "worlddata_core:changes";

    default WDCChangeFilter filter() {
        return WDCChangeFilter.all();
    }
}
