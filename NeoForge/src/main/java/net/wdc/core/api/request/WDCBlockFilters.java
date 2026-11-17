package net.wdc.core.api.request;

import java.util.concurrent.ConcurrentHashMap;

final class WDCBlockFilters {

    private static final int LIMIT = 512;
    private static final ConcurrentHashMap<String, WDCBlockFilter> TAGS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, WDCBlockFilter> NAMES = new ConcurrentHashMap<>();

    private WDCBlockFilters() {}

    static WDCBlockFilter tag(String tagId) {
        if (tagId == null || tagId.isEmpty()) throw new IllegalArgumentException("tagId must not be null or empty");
        if (TAGS.size() >= LIMIT) TAGS.clear();
        return TAGS.computeIfAbsent(tagId, WDCBlockFilter::ofTag);
    }

    static WDCBlockFilter name(String blockId) {
        if (blockId == null || blockId.isEmpty()) throw new IllegalArgumentException("blockId must not be null or empty");
        if (NAMES.size() >= LIMIT) NAMES.clear();
        return NAMES.computeIfAbsent(blockId, WDCBlockFilter::ofId);
    }
}
