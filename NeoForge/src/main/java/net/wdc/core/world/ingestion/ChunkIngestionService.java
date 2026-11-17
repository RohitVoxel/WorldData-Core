package net.wdc.core.world.ingestion;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.wdc.core.api.changes.WDCBlockChange;
import net.wdc.core.config.WDCConfig;
import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.debug.WDCOps;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.DataFreshness;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.change.ChangeSink;
import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.WDCSection;
import net.wdc.core.world.storage.BlockStateKey;
import net.wdc.core.world.storage.BlockToken;
import net.wdc.core.world.storage.StateMeta;
import net.wdc.core.world.storage.ChunkKey;
import net.wdc.core.world.storage.ChunkRegistry;
import net.wdc.core.world.storage.ChunkRegistryProvider;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;
import net.wdc.core.world.storage.SectionBuilder;
import net.wdc.core.world.work.CoreThreads;
import net.minecraft.world.level.block.state.BlockState;

public final class ChunkIngestionService {
    private static final int POSITIONS = 4096;

    private static final class Scratch {
        final SectionUnpacker unpacker = new SectionUnpacker();
        final SectionTarget target = new SectionTarget();
    }

    private final GlobalBlockStateRegistry stateRegistry;
    private final WDCCache cache;
    private final BlockStateInterner interner;
    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);
    private volatile ChangeSink changeSink = ChangeSink.NONE;

    public ChunkIngestionService(GlobalBlockStateRegistry stateRegistry, WDCCache cache) {
        if (stateRegistry == null) throw new IllegalArgumentException("stateRegistry must not be null");
        if (cache == null) throw new IllegalArgumentException("cache must not be null");
        this.stateRegistry = stateRegistry;
        this.cache = cache;
        this.interner = new BlockStateInterner(stateRegistry);
        MinecraftStateMeta.install(stateRegistry);
        this.cache.setEvictionListener((dimensionId, chunkKey) -> {
            WDCChunk removed = ChunkRegistryProvider.forDimension(dimensionId).remove(chunkKey);
            if (removed != null) removed.releaseToPool();
        });
    }

    public int stateIdOf(BlockState state) {
        return interner.idOf(state);
    }

    public int defaultAirId() {
        return stateRegistry.defaultAirId();
    }

    public void setChangeSink(ChangeSink sink) {
        this.changeSink = sink == null ? ChangeSink.NONE : sink;
    }

    public void ingest(WorldKey world, MinecraftChunkView mcChunk) {
        CoreThreads.requireNotWorker("ingest");
        if (!WDCConfig.get().debug.ingestionEnabled) return;
        if (!cache.isCurrentSession(world)) return;
        long start = System.nanoTime();
        String dimensionId = mcChunk.dimensionId();
        int chunkX = mcChunk.chunkX();
        int chunkZ = mcChunk.chunkZ();
        ChunkRegistry chunkRegistry = ChunkRegistryProvider.forDimension(dimensionId);
        ChunkKey key = new ChunkKey(chunkX, chunkZ);
        boolean wasLive = cache.freshnessOf(world, chunkX, chunkZ) == DataFreshness.LIVE;
        IntArrayList changedSections = new IntArrayList();
        MinecraftChunkView.BiomeSample sample = mcChunk.biomeSample();
        cache.onChunkObserved(world, chunkX, chunkZ, () -> {
            int sectionCount = mcChunk.sectionCount();
            WDCChunk wdcChunk = createChunk(chunkRegistry, key, mcChunk, sectionCount);
            boolean changed = installSections(wdcChunk, mcChunk, sectionCount, changedSections);
            if (sample != null && sample.biomeId() != null) {
                wdcChunk.setBiomeSample(sample.biomeId(), sample.rawTemperature(), sample.hasPrecipitation());
            }
            if (changed) wdcChunk.bumpVersion();
            wdcChunk.publishHeights(stateRegistry);
            wdcChunk.markObserved(System.nanoTime());
            return wdcChunk;
        });
        if (wasLive && !changedSections.isEmpty()) {
            changeSink.chunkReread(dimensionId, chunkX, chunkZ, changedSections.elements(), changedSections.size());
        }
        long spent = System.nanoTime() - start;
        WDCMetrics.increment(WDCMetrics.Counter.CHUNK_INGESTIONS);
        WDCMetrics.mark(WDCMetrics.Rate.CHUNK_INGESTIONS);
        WDCOps.record(WDCOps.Kind.INGEST);
        WDCMetrics.record(WDCMetrics.Timer.INGESTION, spent);
    }

    private WDCChunk createChunk(ChunkRegistry chunkRegistry, ChunkKey key, MinecraftChunkView mcChunk, int sectionCount) {
        if (sectionCount <= 0) return chunkRegistry.getOrCreate(key);
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int index = 0; index < sectionCount; index++) {
            int sectionY = mcChunk.sectionYAt(index);
            lowest = Math.min(lowest, sectionY);
            highest = Math.max(highest, sectionY);
        }
        long span = (long) highest - (long) lowest + 1L;
        if (span > WDCChunk.MAX_SLOTS) return chunkRegistry.getOrCreate(key);
        return chunkRegistry.getOrCreate(key, lowest, (int) span);
    }

    private boolean installSections(WDCChunk wdcChunk, MinecraftChunkView mcChunk, int sectionCount, IntArrayList changedSections) {
        boolean changed = false;
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        Scratch work = scratch.get();
        SectionTarget target = work.target;
        for (int index = 0; index < sectionCount; index++) {
            int sectionY = mcChunk.sectionYAt(index);
            lowest = Math.min(lowest, sectionY);
            highest = Math.max(highest, sectionY);
            MinecraftSectionView mcSection = mcChunk.sectionAt(index);
            boolean present = !mcSection.isEmpty() && readSection(mcSection, work, wdcChunk.fingerprintAt(sectionY));
            if (!present || target.kind() == SectionTarget.Kind.EMPTY) {
                if (wdcChunk.removeSection(sectionY)) {
                    changed = true;
                    changedSections.add(sectionY);
                }
                WDCMetrics.increment(WDCMetrics.Counter.EMPTY_SECTIONS_SKIPPED);
                continue;
            }
            boolean sectionChanged;
            if (target.kind() == SectionTarget.Kind.UNCHANGED) {
                WDCMetrics.increment(WDCMetrics.Counter.NON_EMPTY_SECTIONS_PROCESSED);
                continue;
            } else if (target.kind() == SectionTarget.Kind.UNIFORM) {
                sectionChanged = wdcChunk.installUniform(sectionY, target.uniformToken());
                wdcChunk.setFingerprintAt(sectionY, target.fingerprint());
            } else {
                sectionChanged = wdcChunk.installMixed(sectionY, target.builder());
                wdcChunk.setFingerprintAt(sectionY, target.fingerprint());
            }
            if (sectionChanged) {
                changed = true;
                changedSections.add(sectionY);
            }
            WDCMetrics.increment(WDCMetrics.Counter.NON_EMPTY_SECTIONS_PROCESSED);
            WDCMetrics.increment(WDCMetrics.Counter.SECTIONS_FULLY_SCANNED);
        }
        if (sectionCount > 0) {
            for (WDCSection existing : wdcChunk.sectionArray()) {
                int sectionY = existing.sectionY();
                if (sectionY < lowest || sectionY > highest) {
                    wdcChunk.removeSection(sectionY);
                    changed = true;
                    changedSections.add(sectionY);
                }
            }
        }
        return changed;
    }

    private boolean readSection(MinecraftSectionView mcSection, Scratch work, long knownFingerprint) {
        SectionTarget target = work.target;
        target.reset();
        target.expect(knownFingerprint);
        if (mcSection.unpackTokens(interner, work.unpacker, target) && target.kind() != SectionTarget.Kind.NONE) {
            WDCMetrics.increment(WDCMetrics.Counter.INGEST_SECTIONS_RAW);
            return true;
        }
        target.reset();
        WDCMetrics.increment(WDCMetrics.Counter.INGEST_SECTIONS_COMPATIBLE);
        return readSectionCompatible(mcSection, target);
    }

    private boolean readSectionCompatible(MinecraftSectionView mcSection, SectionTarget target) {
        BlockState uniform = mcSection.uniformRawState();
        if (uniform != null) {
            target.setUniform(interner.tokenOf(uniform));
            return true;
        }
        SectionBuilder builder = target.beginMixed();
        char[] out = builder.indices();
        BlockState lastRaw = null;
        int lastToken = BlockToken.AIR;
        int lastLocal = 0;
        int first = 0;
        int diff = 0;
        int index = 0;
        for (int localY = 0; localY < 16; localY++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++, index++) {
                    BlockState raw = mcSection.rawStateAt(localX, localY, localZ);
                    int local;
                    if (raw != null) {
                        if (raw == lastRaw) {
                            local = lastLocal;
                        } else {
                            lastToken = interner.tokenOf(raw);
                            lastRaw = raw;
                            lastLocal = builder.indexOf(lastToken);
                            local = lastLocal;
                        }
                    } else {
                        MinecraftSectionView.BlockStateKeySnapshot snapshot = mcSection.blockStateAt(localX, localY, localZ);
                        local = builder.indexOf(stateRegistry.tokenOf(registerSnapshot(snapshot)));
                        lastRaw = null;
                    }
                    if (index == 0) first = local;
                    diff |= local ^ first;
                    out[index] = (char) local;
                }
            }
        }
        if (diff == 0) {
            target.setUniform(builder.tokenAt(first));
            return true;
        }
        target.setMixed();
        return true;
    }

    public void markUnloaded(WorldKey world, int chunkX, int chunkZ) {
        CoreThreads.requireNotWorker("unload");
        cache.markUnloaded(world, chunkX, chunkZ);
    }

    public boolean isTracked(String dimensionId, int blockX, int blockZ) {
        return ChunkRegistryProvider.forDimension(dimensionId).getAtBlock(blockX, blockZ) != null;
    }

    public void onBlockChanged(String dimensionId, WorldKey world, int blockX, int blockY, int blockZ,
                               MinecraftSectionView.BlockStateKeySnapshot newState) {
        CoreThreads.requireNotWorker("block change");
        if (newState == null) {
            applyRemoval(dimensionId, world, blockX, blockY, blockZ);
            return;
        }
        int globalId = registerSnapshot(newState);
        if (BlockToken.isDefaultAir(stateRegistry.tokenOf(globalId))) {
            applyRemoval(dimensionId, world, blockX, blockY, blockZ);
        } else {
            applyPlacement(dimensionId, world, blockX, blockY, blockZ, globalId);
        }
    }

    public void onBlockStateChanged(String dimensionId, WorldKey world, int blockX, int blockY, int blockZ,
                                    BlockState newState) {
        CoreThreads.requireNotWorker("block change");
        if (newState == null || BlockToken.isDefaultAir(interner.tokenOf(newState))) {
            applyRemoval(dimensionId, world, blockX, blockY, blockZ);
        } else {
            applyPlacement(dimensionId, world, blockX, blockY, blockZ, interner.idOf(newState));
        }
    }

    private void applyRemoval(String dimensionId, WorldKey world, int blockX, int blockY, int blockZ) {
        WDCChunk wdcChunk = ChunkRegistryProvider.forDimension(dimensionId).getAtBlock(blockX, blockZ);
        if (wdcChunk == null) return;
        int previous = wdcChunk.getBlock(blockX, blockY, blockZ);
        if (wdcChunk.removeBlock(blockX, blockY, blockZ)) {
            removeSectionIfNowEmpty(wdcChunk, blockY);
            cache.onIncrementalMutation(world, wdcChunk.chunkX(), wdcChunk.chunkZ());
            reportBlock(dimensionId, blockX, blockY, blockZ, previous, GlobalBlockStateRegistry.NULL_ID);
        }
    }

    private void applyPlacement(String dimensionId, WorldKey world, int blockX, int blockY, int blockZ, int globalId) {
        WDCChunk wdcChunk = ChunkRegistryProvider.forDimension(dimensionId).getAtBlock(blockX, blockZ);
        if (wdcChunk == null) return;
        int previous = wdcChunk.getBlock(blockX, blockY, blockZ);
        if (previous == globalId) return;
        if (wdcChunk.setToken(blockX, blockY, blockZ, stateRegistry.tokenOf(globalId))) wdcChunk.bumpVersion();
        cache.onIncrementalMutation(world, wdcChunk.chunkX(), wdcChunk.chunkZ());
        reportBlock(dimensionId, blockX, blockY, blockZ, previous, globalId);
    }

    private void reportBlock(String dimensionId, int blockX, int blockY, int blockZ, int previousId, int newId) {
        ChangeSink sink = changeSink;
        if (sink.wantsBlockDetails()) {
            sink.blockChanged(dimensionId, blockX, blockY, blockZ, previousId, newId, blockIdOf(previousId), blockIdOf(newId));
        } else {
            sink.blockChanged(dimensionId, blockX, blockY, blockZ, previousId, newId, null, null);
        }
    }

    private String blockIdOf(int stateId) {
        if (stateId == GlobalBlockStateRegistry.NULL_ID) return WDCBlockChange.AIR;
        return stateRegistry.keyOf(stateId).blockId();
    }

    private static void removeSectionIfNowEmpty(WDCChunk wdcChunk, int blockY) {
        int sectionY = WDCChunk.sectionYOf(blockY);
        WDCSection section = wdcChunk.getSection(sectionY);
        if (section != null && wdcChunk.isAllDefaultAir(sectionY)) {
            wdcChunk.removeSection(sectionY);
        }
    }

    private int registerSnapshot(MinecraftSectionView.BlockStateKeySnapshot snapshot) {
        BlockStateKey key = toBlockStateKey(snapshot);
        if (!snapshot.air()) return stateRegistry.registerOrGet(key);
        return stateRegistry.registerOrGet(key, StateMeta.airOf(key));
    }

    private static BlockStateKey toBlockStateKey(MinecraftSectionView.BlockStateKeySnapshot snapshot) {
        return snapshot.properties().isEmpty()
                ? BlockStateKey.of(snapshot.blockId())
                : BlockStateKey.of(snapshot.blockId(), snapshot.properties());
    }
}
