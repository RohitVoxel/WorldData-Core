package net.wdc.core.api.spatial;

import net.wdc.core.api.request.WDCBlockFilter;
import net.wdc.core.api.request.WDCChunkResult;
import net.wdc.core.api.request.WDCChunkView;
import net.wdc.core.api.request.WDCSectionView;
import net.wdc.core.api.request.WDCColumn;
import net.wdc.core.api.request.WDCFreshness;
import net.wdc.core.api.request.WDCMaterial;
import net.wdc.core.api.request.WDCSectionOccupancy;
import net.wdc.core.api.request.BlockVisitor;
import net.wdc.core.debug.monitor.MonitorApi;
import net.wdc.core.debug.monitor.WDCApiMonitor;
import net.wdc.core.world.material.WDCMaterialTag;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;
import net.wdc.core.world.storage.PackedPos;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SpatialApi {

    public static final int MAX_CHUNKS_PER_CALL = 1024;
    public static final int MAX_RADIUS = 192;
    public static final int MAX_Y_SPAN = 4096;
    public static final int MAX_COORDINATE = 30_000_000;
    public static final int MAX_CELL_SIZE = 512;
    public static final int MAX_CELLS = 16_384;

    private static final WDCMaterial[] MATERIALS = WDCMaterial.values();
    private static final WDCMaterial[] BY_INTERNAL = mapInternalMaterials();

    private static WDCMaterial[] mapInternalMaterials() {
        net.wdc.core.world.material.WDCMaterial[] internal = net.wdc.core.world.material.WDCMaterial.values();
        WDCMaterial[] out = new WDCMaterial[internal.length];
        for (int i = 0; i < internal.length; i++) out[i] = WDCMaterial.valueOf(internal[i].name());
        return out;
    }

    private final ChunkReader reader;
    private final ChunkReader peekReader;
    private final GlobalBlockStateRegistry registry;

    public SpatialApi(ChunkReader reader, ChunkReader peekReader, GlobalBlockStateRegistry registry) {
        if (reader == null) throw new IllegalArgumentException("reader must not be null");
        if (peekReader == null) throw new IllegalArgumentException("peekReader must not be null");
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        this.reader = reader;
        this.peekReader = peekReader;
        this.registry = registry;
    }

    public SpatialApi peeking() {
        return new SpatialApi(peekReader, peekReader, registry);
    }

    public WDCRegionSummary summarizeBox(String dimensionId, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean watched = WDCApiMonitor.enabled && WDCApiMonitor.begin(MonitorApi.SUMMARIZE_BOX, WDCApiMonitor.posKey(minX, minY, minZ));
        try {
            return summarize(dimensionId, boxRegion(minX, minY, minZ, maxX, maxY, maxZ));
        } finally {
            WDCApiMonitor.end(watched);
        }
    }

    public WDCRegionSummary summarizeSphere(String dimensionId, int centerX, int centerY, int centerZ, int radius) {
        boolean watched = WDCApiMonitor.enabled && WDCApiMonitor.begin(MonitorApi.SUMMARIZE_SPHERE, WDCApiMonitor.posKey(centerX, centerY, centerZ));
        try {
            return summarize(dimensionId, sphereRegion(centerX, centerY, centerZ, radius));
        } finally {
            WDCApiMonitor.end(watched);
        }
    }

    public WDCVisitResult visitBox(String dimensionId, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                     WDCBlockFilter filter, BlockVisitor visitor) {
        boolean watched = WDCApiMonitor.enabled && WDCApiMonitor.begin(MonitorApi.VISIT_BOX, WDCApiMonitor.posKey(minX, minY, minZ));
        try {
            return visit(dimensionId, boxRegion(minX, minY, minZ, maxX, maxY, maxZ), filter, watched ? WDCApiMonitor.guard(visitor) : visitor);
        } finally {
            WDCApiMonitor.end(watched);
        }
    }

    public WDCVisitResult visitSphere(String dimensionId, int centerX, int centerY, int centerZ, int radius,
                                        WDCBlockFilter filter, BlockVisitor visitor) {
        boolean watched = WDCApiMonitor.enabled && WDCApiMonitor.begin(MonitorApi.VISIT_SPHERE, WDCApiMonitor.posKey(centerX, centerY, centerZ));
        try {
            return visit(dimensionId, sphereRegion(centerX, centerY, centerZ, radius), filter, watched ? WDCApiMonitor.guard(visitor) : visitor);
        } finally {
            WDCApiMonitor.end(watched);
        }
    }

    public WDCVisitResult visitColumn(String dimensionId, int x, int z, int minY, int maxY, BlockVisitor visitor) {
        boolean watched = WDCApiMonitor.enabled && WDCApiMonitor.begin(MonitorApi.VISIT_COLUMN, WDCApiMonitor.posKey(x, minY, z));
        try {
            return columnVisit(dimensionId, x, z, minY, maxY, watched ? WDCApiMonitor.guard(visitor) : visitor);
        } finally {
            WDCApiMonitor.end(watched);
        }
    }

    private WDCVisitResult columnVisit(String dimensionId, int x, int z, int minY, int maxY, BlockVisitor visitor) {
        requireDimension(dimensionId);
        if (visitor == null) throw new IllegalArgumentException("visitor must not be null");
        requireCoordinate(x, "x");
        requireCoordinate(z, "z");
        requireYRange(minY, maxY);

        Tally tally = new Tally();
        WDCChunkResult result = reader.read(dimensionId, x >> 4, z >> 4);
        tally.add(result);
        int visited = 0;
        boolean stopped = false;
        if (isUsable(result)) {
            WDCChunkView chunk = result.raw();
            int localX = x & 15;
            int localZ = z & 15;
            for (int sectionY = minY >> 4, lastSection = maxY >> 4; sectionY <= lastSection && !stopped; sectionY++) {
                WDCSectionView section = chunk.getSection(sectionY);
                if (section == null || section.occupiedCount() == 0) continue;
                int from = Math.max(minY, sectionY << 4);
                int to = Math.min(maxY, (sectionY << 4) + 15);
                for (int y = from; y <= to; y++) {
                    int stateId = section.getBlock(localX, y & 15, localZ);
                    if (stateId == GlobalBlockStateRegistry.NULL_ID) continue;
                    visited++;
                    if (!visitor.visit(x, y, z, stateId, registry.keyOf(stateId).blockId())) {
                        stopped = true;
                        break;
                    }
                }
            }
        }
        return new WDCVisitResult(visited, stopped, tally.done());
    }

    public WDCGridSample sampleGrid(String dimensionId, int minX, int minZ, int maxX, int maxZ, int cellSize) {
        boolean watched = WDCApiMonitor.enabled && WDCApiMonitor.begin(MonitorApi.SAMPLE_GRID, WDCApiMonitor.posKey(minX, cellSize, minZ));
        try {
            return gridSample(dimensionId, minX, minZ, maxX, maxZ, cellSize);
        } finally {
            WDCApiMonitor.end(watched);
        }
    }

    private WDCGridSample gridSample(String dimensionId, int minX, int minZ, int maxX, int maxZ, int cellSize) {
        requireDimension(dimensionId);
        requireCoordinate(minX, "minX");
        requireCoordinate(maxX, "maxX");
        requireCoordinate(minZ, "minZ");
        requireCoordinate(maxZ, "maxZ");
        if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("min must not exceed max");
        if (cellSize < 1 || cellSize > MAX_CELL_SIZE) {
            throw new IllegalArgumentException("cellSize must be 1.." + MAX_CELL_SIZE + " but was " + cellSize);
        }

        int cellMinX = Math.floorDiv(minX, cellSize);
        int cellMinZ = Math.floorDiv(minZ, cellSize);
        int cellsX = Math.floorDiv(maxX, cellSize) - cellMinX + 1;
        int cellsZ = Math.floorDiv(maxZ, cellSize) - cellMinZ + 1;
        if ((long) cellsX * cellsZ > MAX_CELLS) {
            throw new IllegalArgumentException("grid would have " + ((long) cellsX * cellsZ) + " cells; the limit is " + MAX_CELLS);
        }
        int originX = cellMinX * cellSize;
        int originZ = cellMinZ * cellSize;
        SpatialRegion span = SpatialRegion.box(originX, 0, originZ,
                originX + cellsX * cellSize - 1, 0, originZ + cellsZ * cellSize - 1);
        checkChunkCount(span);

        Long2ObjectOpenHashMap<WDCColumn> columns = new Long2ObjectOpenHashMap<>();
        WDCCoverage coverage = forEachChunk(dimensionId, span, (result, chunkX, chunkZ) -> {
            WDCColumn column = result.column();
            if (column != null) columns.put(chunkKey(chunkX, chunkZ), column);
            return true;
        });

        int stride = Math.max(1, cellSize / 4);
        int samplesPerAxis = (cellSize + stride - 1) / stride;
        List<WDCGridCell> cells = new ArrayList<>(cellsX * cellsZ);
        for (int iz = 0; iz < cellsZ; iz++) {
            for (int ix = 0; ix < cellsX; ix++) {
                cells.add(sampleCell(columns, originX + ix * cellSize, originZ + iz * cellSize, cellSize, stride, samplesPerAxis));
            }
        }
        return new WDCGridSample(coverage, cellSize, originX, originZ, cellsX, cellsZ, cells);
    }

    private static WDCGridCell sampleCell(Long2ObjectOpenHashMap<WDCColumn> columns, int minX, int minZ, int size, int stride, int samplesPerAxis) {
        int sampled = 0;
        int solid = 0;
        int water = 0;
        long solidSum = 0L;
        int minSolid = Integer.MAX_VALUE;
        int maxSolid = Integer.MIN_VALUE;
        long lastKey = Long.MIN_VALUE;
        WDCColumn column = null;
        for (int sz = 0; sz < samplesPerAxis; sz++) {
            int z = minZ + Math.min(size - 1, sz * stride + stride / 2);
            for (int sx = 0; sx < samplesPerAxis; sx++) {
                int x = minX + Math.min(size - 1, sx * stride + stride / 2);
                long key = chunkKey(x >> 4, z >> 4);
                if (key != lastKey) {
                    lastKey = key;
                    column = columns.get(key);
                }
                if (column == null) continue;
                sampled++;
                int localX = x & 15;
                int localZ = z & 15;
                int top = column.topSolidY(localX, localZ);
                if (top != WDCColumn.UNKNOWN) {
                    solid++;
                    solidSum += top;
                    minSolid = Math.min(minSolid, top);
                    maxSolid = Math.max(maxSolid, top);
                }
                if (column.isWaterColumn(localX, localZ)) water++;
            }
        }
        double average = solid > 0 ? solidSum / (double) solid : Double.NaN;
        if (solid == 0) {
            minSolid = WDCColumn.UNKNOWN;
            maxSolid = WDCColumn.UNKNOWN;
        }
        double waterFraction = sampled > 0 ? water / (double) sampled : 0.0;
        return new WDCGridCell(minX, minZ, size, sampled, solid, average, minSolid, maxSolid, waterFraction);
    }

    private WDCRegionSummary summarize(String dimensionId, SpatialRegion region) {
        requireDimension(dimensionId);
        long[] counts = new long[MATERIALS.length];
        long[] volume = new long[1];
        int minSectionY = region.minY >> 4;
        int maxSectionY = region.maxY >> 4;

        WDCCoverage coverage = forEachChunk(dimensionId, region, (result, chunkX, chunkZ) -> {
            volume[0] += region.voxelsInChunk(chunkX, chunkZ);
            WDCChunkView chunk = result.raw();
            WDCSectionOccupancy occupancy = result.occupancy();
            for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                WDCSectionView section = chunk.getSection(sectionY);
                if (section == null || section.occupiedCount() == 0) continue;
                int baseX = chunkX << 4;
                int baseY = sectionY << 4;
                int baseZ = chunkZ << 4;
                if (occupancy != null && region.containsCube(baseX, baseY, baseZ, 16)) {
                    for (WDCMaterial material : MATERIALS) {
                        if (material == WDCMaterial.AIR) continue;
                        counts[material.ordinal()] += occupancy.countOf(sectionY, material);
                    }
                } else {
                    countPartialSection(section, region, baseX, baseY, baseZ, counts);
                }
            }
            return true;
        });

        long nonAir = 0L;
        for (WDCMaterial material : MATERIALS) {
            if (material != WDCMaterial.AIR) nonAir += counts[material.ordinal()];
        }
        counts[WDCMaterial.AIR.ordinal()] = Math.max(0L, volume[0] - nonAir);
        return new WDCRegionSummary(coverage, volume[0], counts);
    }

    private void countPartialSection(WDCSectionView section, SpatialRegion region, int baseX, int baseY, int baseZ, long[] counts) {
        int localMin = Math.max(0, region.minY - baseY);
        int localMax = Math.min(15, region.maxY - baseY);
        if (localMin > localMax) return;
        boolean columnsCovered = region.isBox() && region.coversColumns(baseX >> 4, baseZ >> 4);
        int limit = section.stateEntryLimit();
        if (limit == 0) return;
        Scratch scratch = SCRATCH.get();
        int[] perSlot = scratch.perSlot(limit + 1);
        int from = localMin << 8;
        int to = (localMax + 1) << 8;
        if (columnsCovered) {
            section.countSlotsInRange(from, to, perSlot);
        } else if (region.isBox()) {
            int lo = Math.max(region.minX - baseX, 0);
            int hi = Math.min(region.maxX - baseX, 15);
            int zLow = Math.max(region.minZ - baseZ, 0);
            int zHigh = Math.min(region.maxZ - baseZ, 15);
            if (lo <= hi) {
                for (int localY = localMin; localY <= localMax; localY++) {
                    for (int localZ = zLow; localZ <= zHigh; localZ++) {
                        int rowBase = (localY << 8) | (localZ << 4);
                        section.countSlotsInRange(rowBase + lo, rowBase + hi + 1, perSlot);
                    }
                }
            }
        } else if (region.isSphere()) {
            int centerX = region.centerX();
            for (int localY = localMin; localY <= localMax; localY++) {
                int worldY = baseY + localY;
                for (int localZ = 0; localZ < 16; localZ++) {
                    int half = region.rowHalfWidth(worldY, baseZ + localZ);
                    if (half < 0) continue;
                    int lo = Math.max(centerX - half - baseX, 0);
                    int hi = Math.min(centerX + half - baseX, 15);
                    if (lo > hi) continue;
                    int rowBase = (localY << 8) | (localZ << 4);
                    section.countSlotsInRange(rowBase + lo, rowBase + hi + 1, perSlot);
                }
            }
        } else {
            char[] slots = scratch.slots;
            section.copySlotRange(from, to, slots);
            for (int i = 0, n = to - from; i < n; i++) {
                int slot = slots[i];
                if (slot == 0) continue;
                int packed = from + i;
                if (region.contains(baseX + PackedPos.unpackX(packed), baseY + PackedPos.unpackY(packed),
                        baseZ + PackedPos.unpackZ(packed))) {
                    perSlot[slot]++;
                }
            }
        }
        for (int slot = 1; slot <= limit; slot++) {
            int count = perSlot[slot];
            if (count == 0) continue;
            counts[materialOf(section.stateEntryId(slot - 1)).ordinal()] += count;
        }
    }

    private WDCVisitResult visit(String dimensionId, SpatialRegion region, WDCBlockFilter filter, BlockVisitor visitor) {
        requireDimension(dimensionId);
        if (visitor == null) throw new IllegalArgumentException("visitor must not be null");
        int minSectionY = region.minY >> 4;
        int maxSectionY = region.maxY >> 4;
        int[] visited = new int[1];
        boolean[] stop = new boolean[1];
        Scratch scratch = SCRATCH.get();

        WDCCoverage coverage = forEachChunk(dimensionId, region, (result, chunkX, chunkZ) -> {
            WDCChunkView chunk = result.raw();
            boolean columnsCovered = region.isBox() && region.coversColumns(chunkX, chunkZ);
            for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                WDCSectionView section = chunk.getSection(sectionY);
                if (section == null || section.occupiedCount() == 0) continue;
                int baseX = chunkX << 4;
                int baseY = sectionY << 4;
                int baseZ = chunkZ << 4;
                int localMin = Math.max(0, region.minY - baseY);
                int localMax = Math.min(15, region.maxY - baseY);
                if (localMin > localMax) continue;
                int limit = section.stateEntryLimit();
                boolean[] match = scratch.match(limit + 1);
                boolean any = false;
                for (int entry = 0; entry < limit; entry++) {
                    if (section.stateEntryCount(entry) == 0) continue;
                    int stateId = section.stateEntryId(entry);
                    if (filter != null && !filter.testStateCached(stateId, registry)) continue;
                    match[entry + 1] = true;
                    scratch.ids[entry + 1] = stateId;
                    scratch.names[entry + 1] = registry.keyOf(stateId).blockId();
                    any = true;
                }
                if (!any) continue;
                char[] positions = scratch.positions;
                int found = section.collectPositionsAndSlots(match, localMin << 8, (localMax + 1) << 8, positions, scratch.slots);
                for (int i = 0; i < found; i++) {
                    int packed = positions[i];
                    int x = baseX + PackedPos.unpackX(packed);
                    int y = baseY + PackedPos.unpackY(packed);
                    int z = baseZ + PackedPos.unpackZ(packed);
                    if (!columnsCovered && !region.contains(x, y, z)) continue;
                    int slot = scratch.slots[i];
                    visited[0]++;
                    if (!visitor.visit(x, y, z, scratch.ids[slot], scratch.names[slot])) {
                        stop[0] = true;
                        break;
                    }
                }
                scratch.clearMatch(limit + 1);
                if (stop[0]) return false;
            }
            return true;
        });
        return new WDCVisitResult(visited[0], stop[0], coverage);
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static final class Scratch {
        private int[] slotCounts = new int[16];
        private boolean[] matches = new boolean[16];
        final char[] positions = new char[4096];
        final char[] slots = new char[4096];
        int[] ids = new int[16];
        String[] names = new String[16];

        int[] perSlot(int size) {
            if (slotCounts.length < size) slotCounts = new int[Math.max(size, slotCounts.length * 2)];
            java.util.Arrays.fill(slotCounts, 0, size, 0);
            return slotCounts;
        }

        boolean[] match(int size) {
            if (matches.length < size) {
                int grown = Math.max(size, matches.length * 2);
                matches = new boolean[grown];
                ids = new int[grown];
                names = new String[grown];
            }
            return matches;
        }

        void clearMatch(int size) {
            java.util.Arrays.fill(matches, 0, Math.min(size, matches.length), false);
            java.util.Arrays.fill(names, 0, Math.min(size, names.length), null);
        }
    }

    private WDCMaterial materialOf(int stateId) {
        WDCMaterialTag tag = registry.materialTagOf(stateId);
        if (tag == null || tag.material() == null) return WDCMaterial.UNKNOWN;
        return BY_INTERNAL[tag.material().ordinal()];
    }

    private interface ChunkSink {
        boolean accept(WDCChunkResult result, int chunkX, int chunkZ);
    }

    private WDCCoverage forEachChunk(String dimensionId, SpatialRegion region, ChunkSink sink) {
        Tally tally = new Tally();
        outer:
        for (int chunkZ = region.minChunkZ(); chunkZ <= region.maxChunkZ(); chunkZ++) {
            for (int chunkX = region.minChunkX(); chunkX <= region.maxChunkX(); chunkX++) {
                WDCChunkResult result = reader.read(dimensionId, chunkX, chunkZ);
                tally.add(result);
                if (isUsable(result) && !sink.accept(result, chunkX, chunkZ)) break outer;
            }
        }
        return tally.done();
    }

    private static boolean isUsable(WDCChunkResult result) {
        return result != null && result.isUsable() && result.raw() != null;
    }

    private static final class Tally {
        private int chunks;
        private int live;
        private int stale;
        private int acquiring;
        private int failed;

        void add(WDCChunkResult result) {
            chunks++;
            if (result == null) return;
            if (isUsable(result)) {
                if (result.freshness() == WDCFreshness.LIVE) live++;
                else stale++;
            } else if (result.isAcquiring()) {
                acquiring++;
            } else if (result.hasFailed()) {
                failed++;
            }
        }

        WDCCoverage done() {
            return new WDCCoverage(chunks, live, stale, acquiring, failed);
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static SpatialRegion boxRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        requireCoordinate(minX, "minX");
        requireCoordinate(maxX, "maxX");
        requireCoordinate(minZ, "minZ");
        requireCoordinate(maxZ, "maxZ");
        if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("min must not exceed max");
        requireYRange(minY, maxY);
        SpatialRegion region = SpatialRegion.box(minX, minY, minZ, maxX, maxY, maxZ);
        checkChunkCount(region);
        return region;
    }

    private static SpatialRegion sphereRegion(int centerX, int centerY, int centerZ, int radius) {
        requireCoordinate(centerX, "centerX");
        requireCoordinate(centerY, "centerY");
        requireCoordinate(centerZ, "centerZ");
        if (radius < 0 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("radius must be 0.." + MAX_RADIUS + " but was " + radius);
        }
        SpatialRegion region = SpatialRegion.sphere(centerX, centerY, centerZ, radius);
        checkChunkCount(region);
        return region;
    }

    private static void checkChunkCount(SpatialRegion region) {
        long chunks = region.chunkCount();
        if (chunks > MAX_CHUNKS_PER_CALL) {
            throw new IllegalArgumentException("region spans " + chunks + " chunks; the limit is " + MAX_CHUNKS_PER_CALL + " per call");
        }
    }

    private static void requireDimension(String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) throw new IllegalArgumentException("dimensionId must not be blank");
    }

    private static void requireCoordinate(int value, String name) {
        if (value < -MAX_COORDINATE || value > MAX_COORDINATE) {
            throw new IllegalArgumentException(name + " must be within +-" + MAX_COORDINATE + " but was " + value);
        }
    }

    private static void requireYRange(int minY, int maxY) {
        requireCoordinate(minY, "minY");
        requireCoordinate(maxY, "maxY");
        if (minY > maxY) throw new IllegalArgumentException("minY must not exceed maxY");
        if ((long) maxY - minY + 1L > MAX_Y_SPAN) {
            throw new IllegalArgumentException("y span must be at most " + MAX_Y_SPAN);
        }
    }
}
