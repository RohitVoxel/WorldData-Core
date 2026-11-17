package net.wdc.core.api.request;

import net.wdc.core.debug.WDCOps;
import net.wdc.core.debug.monitor.MonitorApi;
import net.wdc.core.debug.monitor.MonitorOutcome;
import net.wdc.core.debug.monitor.WDCApiMonitor;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.demand.WDCDemandService;
import net.wdc.core.world.demand.ChunkBlockIndex;
import net.wdc.core.world.demand.ChunkColumnCache;
import net.wdc.core.world.demand.ChunkHeightmap;
import net.wdc.core.world.demand.DataLayer;
import net.wdc.core.world.demand.ChunkOccupancy;
import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.BlockToken;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;

import java.util.Arrays;
import java.util.List;

public final class WDCChunkResult {

    private final int chunkX;
    private final int chunkZ;
    private final WDCFreshness freshness;
    private final WDCChunk value;
    private final GlobalBlockStateRegistry registry;
    private final WDCDemandService demandService;
    private final WorldKey world;
    private final WDCAcquisitionStatus acquisitionStatus;
    private WDCChunkView rawView;

    WDCChunkResult(int chunkX, int chunkZ, WDCFreshness freshness, WDCChunk value, GlobalBlockStateRegistry registry) {
        this(chunkX, chunkZ, freshness, value, registry, null, null);
    }

    WDCChunkResult(int chunkX, int chunkZ, WDCFreshness freshness, WDCChunk value, GlobalBlockStateRegistry registry,
                     WDCDemandService demandService, WorldKey world) {
        this(chunkX, chunkZ, freshness, value, registry, demandService, world, null);
    }

    public WDCChunkResult(int chunkX, int chunkZ, WDCFreshness freshness, WDCChunk value, GlobalBlockStateRegistry registry,
                            WDCDemandService demandService, WorldKey world, WDCAcquisitionStatus acquisitionStatus) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.freshness = freshness == null ? WDCFreshness.UNAVAILABLE : freshness;
        this.value = value;
        this.registry = registry;
        this.demandService = demandService;
        this.world = world;
        this.acquisitionStatus = acquisitionStatus != null ? acquisitionStatus : defaultStatus(this.freshness);
    }

    private static WDCAcquisitionStatus defaultStatus(WDCFreshness freshness) {
        return freshness == WDCFreshness.UNAVAILABLE ? WDCAcquisitionStatus.NOT_REQUESTED : WDCAcquisitionStatus.DELIVERED;
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public WDCFreshness freshness() { return freshness; }
    public long version() { return value == null ? -1L : value.version(); }
    public boolean isUsable() { return freshness == WDCFreshness.LIVE || freshness == WDCFreshness.STALE; }
    public WDCAcquisitionStatus acquisitionStatus() { return acquisitionStatus; }
    public boolean isUnloaded() { return acquisitionStatus == WDCAcquisitionStatus.DATA_UNLOADED; }
    public boolean isAcquiring() { return acquisitionStatus == WDCAcquisitionStatus.PENDING; }
    public boolean hasFailed() { return acquisitionStatus.isFinal() && acquisitionStatus != WDCAcquisitionStatus.DELIVERED; }

    public long ageMillis() {
        if (value == null || value.observedNanos() == 0L) return -1L;
        return Math.max(0L, (System.nanoTime() - value.observedNanos()) / 1_000_000L);
    }

    public WDCChunkView raw() {
        if (WDCApiMonitor.enabled) watchChunk(MonitorApi.RAW);
        if (value == null) return null;
        WDCChunkView view = rawView;
        if (view == null) {
            view = new WDCChunkView(value);
            rawView = view;
        }
        return view;
    }

    private WDCBlockQuery query() {
        Object handle = registry.scanHandle();
        if (handle instanceof WDCBlockQuery query) return query;
        WDCBlockQuery created = new WDCBlockQuery(registry);
        registry.setScanHandle(created);
        return created;
    }

    private boolean contains(int blockX, int blockZ) {
        return (blockX >> 4) == chunkX && (blockZ >> 4) == chunkZ;
    }

    MonitorOutcome monitorOutcome() {
        if (freshness == WDCFreshness.LIVE) return MonitorOutcome.LIVE;
        if (freshness == WDCFreshness.STALE) return MonitorOutcome.STALE;
        return switch (acquisitionStatus) {
            case DATA_UNLOADED -> MonitorOutcome.UNLOADED;
            case PENDING -> MonitorOutcome.PENDING;
            default -> MonitorOutcome.UNAVAILABLE;
        };
    }

    private void watchAt(MonitorApi api, int blockX, int blockY, int blockZ) {
        WDCApiMonitor.hit(api, monitorOutcome(), WDCApiMonitor.posKey(blockX, blockY, blockZ));
    }

    private void watchChunk(MonitorApi api) {
        WDCApiMonitor.hit(api, monitorOutcome(), WDCApiMonitor.chunkKey(chunkX, chunkZ));
    }

    private void watchScan(MonitorApi api, long what) {
        WDCApiMonitor.hit(api, monitorOutcome(), WDCApiMonitor.mixKey(WDCApiMonitor.chunkKey(chunkX, chunkZ), what));
    }

    private static long filterKey(WDCBlockFilter filter) {
        return filter == null ? 0L : System.identityHashCode(filter);
    }

    public int stateIdAt(int blockX, int blockY, int blockZ) {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchAt(MonitorApi.STATE_ID_AT, blockX, blockY, blockZ);
        return rawStateIdAt(blockX, blockY, blockZ);
    }

    private int rawStateIdAt(int blockX, int blockY, int blockZ) {
        if (!isUsable() || value == null || !contains(blockX, blockZ) || !value.containsBlockY(blockY)) {
            return GlobalBlockStateRegistry.NULL_ID;
        }
        int token = value.getToken(blockX, blockY, blockZ);
        if (!BlockToken.isDefaultAir(token)) return BlockToken.globalIdOf(token);
        return registry == null ? GlobalBlockStateRegistry.NULL_ID : registry.defaultAirId();
    }

    public boolean isPresent(int blockX, int blockY, int blockZ) {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchAt(MonitorApi.IS_PRESENT, blockX, blockY, blockZ);
        if (!isUsable() || value == null || !contains(blockX, blockZ) || !value.containsBlockY(blockY)) return false;
        return !BlockToken.isAir(value.getToken(blockX, blockY, blockZ));
    }

    private List<WDCBlockMatch> scanFind(WDCBlockFilter filter) {
        if (!isUsable() || registry == null) return List.of();
        return query().find(value, filter);
    }

    private void scanVisit(WDCBlockFilter filter, BlockVisitor visitor) {
        if (!isUsable() || registry == null) return;
        query().visit(value, filter, visitor);
    }

    private boolean scanAny(WDCBlockFilter filter) {
        if (!isUsable() || registry == null) return false;
        return query().any(value, filter);
    }

    private int scanCount(WDCBlockFilter filter) {
        if (!isUsable() || registry == null) return 0;
        return query().count(value, filter);
    }

    public List<WDCBlockMatch> findBlocks(WDCBlockFilter filter) {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchScan(MonitorApi.FIND_BLOCKS, filterKey(filter));
        return scanFind(filter);
    }

    public void visitBlocks(WDCBlockFilter filter, BlockVisitor visitor) {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchScan(MonitorApi.VISIT_BLOCKS, filterKey(filter));
        scanVisit(filter, visitor);
    }

    public boolean hasBlock(WDCBlockFilter filter) {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchScan(MonitorApi.HAS_BLOCK, filterKey(filter));
        return scanAny(filter);
    }

    public int countBlocks(WDCBlockFilter filter) {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchScan(MonitorApi.COUNT_BLOCKS, filterKey(filter));
        return scanCount(filter);
    }

    public List<WDCBlockMatch> findBlocksByTag(String tagId) {
        WDCBlockFilter filter = WDCBlockFilters.tag(tagId);
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchScan(MonitorApi.FIND_BLOCKS_BY_TAG, WDCApiMonitor.textKey(tagId));
        return scanFind(filter);
    }

    public List<WDCBlockMatch> findBlocksByName(String blockId) {
        WDCBlockFilter filter = WDCBlockFilters.name(blockId);
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchScan(MonitorApi.FIND_BLOCKS_BY_NAME, WDCApiMonitor.textKey(blockId));
        return scanFind(filter);
    }

    public List<WDCBlockMatch> findBlocksByStateId(int... stateIds) {
        WDCBlockFilter filter = WDCBlockFilter.ofStateId(stateIds);
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchScan(MonitorApi.FIND_BLOCKS_BY_STATE_ID, Arrays.hashCode(stateIds));
        return scanFind(filter);
    }

    public int countBlocksByTag(String tagId) {
        WDCBlockFilter filter = WDCBlockFilters.tag(tagId);
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchScan(MonitorApi.COUNT_BLOCKS_BY_TAG, WDCApiMonitor.textKey(tagId));
        return scanCount(filter);
    }

    public int countBlocksByName(String blockId) {
        WDCBlockFilter filter = WDCBlockFilters.name(blockId);
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchScan(MonitorApi.COUNT_BLOCKS_BY_NAME, WDCApiMonitor.textKey(blockId));
        return scanCount(filter);
    }

    public int countBlocksByStateId(int... stateIds) {
        WDCBlockFilter filter = WDCBlockFilter.ofStateId(stateIds);
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchScan(MonitorApi.COUNT_BLOCKS_BY_STATE_ID, Arrays.hashCode(stateIds));
        return scanCount(filter);
    }

    public boolean hasBlockByTag(String tagId) {
        WDCBlockFilter filter = WDCBlockFilters.tag(tagId);
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchScan(MonitorApi.HAS_BLOCK_BY_TAG, WDCApiMonitor.textKey(tagId));
        return scanAny(filter);
    }

    public boolean hasBlockByName(String blockId) {
        WDCBlockFilter filter = WDCBlockFilters.name(blockId);
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchScan(MonitorApi.HAS_BLOCK_BY_NAME, WDCApiMonitor.textKey(blockId));
        return scanAny(filter);
    }

    public boolean hasBlockByStateId(int... stateIds) {
        WDCBlockFilter filter = WDCBlockFilter.ofStateId(stateIds);
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchScan(MonitorApi.HAS_BLOCK_BY_STATE_ID, Arrays.hashCode(stateIds));
        return scanAny(filter);
    }

    public boolean hasTagAt(int blockX, int blockY, int blockZ, WDCStateTag tag) {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchAt(MonitorApi.HAS_TAG_AT, blockX, blockY, blockZ);
        if (!isUsable() || registry == null || tag == null) return false;
        int stateId = rawStateIdAt(blockX, blockY, blockZ);
        if (stateId == GlobalBlockStateRegistry.NULL_ID) return false;
        return (registry.tagMaskOf(stateId) & tag.bit()) != 0;
    }

    public WDCMaterial materialAt(int blockX, int blockY, int blockZ) {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchAt(MonitorApi.MATERIAL_AT, blockX, blockY, blockZ);
        if (!isUsable() || registry == null) return WDCMaterial.UNKNOWN;
        int stateId = rawStateIdAt(blockX, blockY, blockZ);
        if (stateId == GlobalBlockStateRegistry.NULL_ID) return WDCMaterial.UNKNOWN;
        return WDCMaterial.from(registry.materialTagOf(stateId).material());
    }

    public WDCBlockInfo blockAt(int blockX, int blockY, int blockZ) {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchAt(MonitorApi.BLOCK_AT, blockX, blockY, blockZ);
        if (!isUsable() || registry == null) return WDCBlockInfo.ABSENT;
        return WDCBlockInfos.of(registry, rawStateIdAt(blockX, blockY, blockZ));
    }

    public boolean matchesAt(int blockX, int blockY, int blockZ, WDCBlockFilter filter) {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchAt(MonitorApi.MATCHES_AT, blockX, blockY, blockZ);
        if (!isUsable() || registry == null || filter == null) return false;
        int stateId = rawStateIdAt(blockX, blockY, blockZ);
        if (stateId == GlobalBlockStateRegistry.NULL_ID) return false;
        return filter.testStateCached(stateId, registry);
    }

    private WDCWaterInfo rawWaterAt(int blockX, int blockY, int blockZ) {
        if (!isUsable() || registry == null) return WDCWaterInfo.NONE;
        return WDCBlockInfos.waterOf(registry, rawStateIdAt(blockX, blockY, blockZ));
    }

    public WDCWaterInfo waterAt(int blockX, int blockY, int blockZ) {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchAt(MonitorApi.WATER_AT, blockX, blockY, blockZ);
        return rawWaterAt(blockX, blockY, blockZ);
    }

    public boolean isSubmerged(int blockX, int blockY, int blockZ) {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchAt(MonitorApi.IS_SUBMERGED, blockX, blockY, blockZ);
        return rawWaterAt(blockX, blockY, blockZ).isWater();
    }

    public WDCBiomeInfo biome() {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchChunk(MonitorApi.BIOME);
        if (!isUsable() || value == null || !value.biomePresent()) return WDCBiomeInfo.NONE;
        return new WDCBiomeInfo(value.biomeId(), value.rawTemperature(), value.hasPrecipitation());
    }

    public WDCHeightmap heightmap() {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchChunk(MonitorApi.HEIGHTMAP);
        if (!isUsable() || demandService == null || value == null) return null;
        Object layer = demandService.layerOf(value, DataLayer.HEIGHTMAP);
        return layer instanceof ChunkHeightmap heightmap ? new WDCHeightmap(heightmap) : null;
    }

    public WDCColumn column() {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchChunk(MonitorApi.COLUMN);
        if (!isUsable() || demandService == null || value == null) return null;
        Object layer = demandService.layerOf(value, DataLayer.COLUMN);
        return layer instanceof ChunkColumnCache columnCache ? new WDCColumn(columnCache, registry) : null;
    }

    public WDCBlockIndex blockIndex() {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchChunk(MonitorApi.BLOCK_INDEX);
        if (!isUsable() || demandService == null || value == null) return null;
        Object layer = demandService.layerOf(value, DataLayer.BLOCK_INDEX);
        return layer instanceof ChunkBlockIndex blockIndex ? new WDCBlockIndex(blockIndex, registry) : null;
    }

    public WDCSectionOccupancy occupancy() {
        WDCOps.query();
        if (WDCApiMonitor.enabled) watchChunk(MonitorApi.OCCUPANCY);
        if (!isUsable() || demandService == null || value == null) return null;
        Object layer = demandService.layerOf(value, DataLayer.OCCUPANCY);
        return layer instanceof ChunkOccupancy occupancy ? new WDCSectionOccupancy(occupancy) : null;
    }
}
