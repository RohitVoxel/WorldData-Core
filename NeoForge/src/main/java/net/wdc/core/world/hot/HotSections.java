package net.wdc.core.world.hot;

import net.wdc.core.config.HotSectionsConfig;
import net.wdc.core.world.cache.DataFreshness;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.WorldKey;
import net.wdc.core.world.storage.HeatScan;
import net.wdc.core.world.storage.WDCChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

public final class HotSections implements WDCCache.PublishListener {

    public static final int WINDOW_TICKS = 20;
    public static final int MAX_PROMOTIONS_PER_WINDOW = 8;
    public static final long MAX_AREA_SECTIONS = 4096L;
    public static final long SECTION_BYTES = WDCChunk.HOT_SECTION_BYTES;

    public enum PinResult { PINNED, ALREADY_PINNED, BUDGET_FULL, DISABLED }

    public record PinKey(String dimensionId, int chunkX, int sectionY, int chunkZ) {}

    public record AreaResult(int pinned, int alreadyPinned, int refused, long sections, boolean tooLarge) {}

    public record Status(int pinned, int auto, int maxSections, long usedBytes, long maxBytes, boolean autoPromote,
                         long promotions, long demotions, long refusals) {
        public int used() {
            return pinned + auto;
        }
    }

    private record ChunkRef(String dimensionId, int chunkX, int chunkZ) {}

    private record Candidate(WDCChunk chunk, int sectionY, int reads) {}

    private final WDCCache cache;
    private final Object lock = new Object();
    private final LinkedHashSet<PinKey> pins = new LinkedHashSet<>();
    private final Map<ChunkRef, Set<Integer>> pinsByChunk = new HashMap<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private final AtomicLong promotions = new AtomicLong();
    private final AtomicLong demotions = new AtomicLong();
    private final AtomicLong refusals = new AtomicLong();
    private volatile HotSectionsConfig config;
    private volatile int pinCount;
    private volatile int autoHot;
    private int tickCounter;

    public HotSections(WDCCache cache, HotSectionsConfig config) {
        if (cache == null) throw new IllegalArgumentException("cache must not be null");
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.cache = cache;
        this.config = config.copy();
        cache.setPublishListener(this);
    }

    public HotSectionsConfig config() {
        return config.copy();
    }

    public void applyConfig(HotSectionsConfig next) {
        if (next == null) throw new IllegalArgumentException("config must not be null");
        next.validate();
        this.config = next.copy();
        if (!next.autoPromote || pinCount + autoHot > next.maxSections) shedAllAuto();
    }

    public int sectionBudget() {
        return config.maxSections;
    }

    public PinResult pin(String dimensionId, int blockX, int blockY, int blockZ) {
        return pinSection(dimensionId, blockX >> 4, blockY >> 4, blockZ >> 4);
    }

    public PinResult pinSection(String dimensionId, int chunkX, int sectionY, int chunkZ) {
        PinKey key = new PinKey(dimensionId, chunkX, sectionY, chunkZ);
        synchronized (lock) {
            int max = config.maxSections;
            if (max <= 0) return PinResult.DISABLED;
            if (pins.contains(key)) return PinResult.ALREADY_PINNED;
            while (pins.size() + autoHot >= max) {
                if (pins.size() >= max) {
                    refusals.incrementAndGet();
                    return PinResult.BUDGET_FULL;
                }
                if (!shedOneAuto()) autoHot = 0;
            }
            pins.add(key);
            pinsByChunk.computeIfAbsent(new ChunkRef(dimensionId, chunkX, chunkZ), k -> new TreeSet<>()).add(sectionY);
            pinCount = pins.size();
        }
        applyPin(key);
        return PinResult.PINNED;
    }

    public AreaResult pinArea(String dimensionId, int x1, int y1, int z1, int x2, int y2, int z2) {
        int minCx = Math.min(x1, x2) >> 4;
        int maxCx = Math.max(x1, x2) >> 4;
        int minSy = Math.min(y1, y2) >> 4;
        int maxSy = Math.max(y1, y2) >> 4;
        int minCz = Math.min(z1, z2) >> 4;
        int maxCz = Math.max(z1, z2) >> 4;
        long sections = (long) (maxCx - minCx + 1) * (maxSy - minSy + 1) * (maxCz - minCz + 1);
        if (sections > MAX_AREA_SECTIONS) return new AreaResult(0, 0, 0, sections, true);
        int pinned = 0;
        int already = 0;
        int refused = 0;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (int sy = minSy; sy <= maxSy; sy++) {
                    switch (pinSection(dimensionId, cx, sy, cz)) {
                        case PINNED -> pinned++;
                        case ALREADY_PINNED -> already++;
                        default -> refused++;
                    }
                }
            }
        }
        return new AreaResult(pinned, already, refused, sections, false);
    }

    public boolean unpin(String dimensionId, int blockX, int blockY, int blockZ) {
        return unpinSection(dimensionId, blockX >> 4, blockY >> 4, blockZ >> 4);
    }

    public boolean unpinSection(String dimensionId, int chunkX, int sectionY, int chunkZ) {
        PinKey key = new PinKey(dimensionId, chunkX, sectionY, chunkZ);
        synchronized (lock) {
            if (!pins.remove(key)) return false;
            ChunkRef ref = new ChunkRef(dimensionId, chunkX, chunkZ);
            Set<Integer> levels = pinsByChunk.get(ref);
            if (levels != null) {
                levels.remove(sectionY);
                if (levels.isEmpty()) pinsByChunk.remove(ref);
            }
            pinCount = pins.size();
        }
        WDCChunk chunk = chunkOf(dimensionId, chunkX, chunkZ);
        if (chunk != null) {
            boolean wasHot = chunk.isHot(sectionY);
            chunk.setPinned(sectionY, false);
            if (wasHot) {
                if (config.autoPromote) autoHot++;
                else if (chunk.demoteHot(sectionY)) demotions.incrementAndGet();
            }
        }
        return true;
    }

    public List<PinKey> pins() {
        synchronized (lock) {
            return new ArrayList<>(pins);
        }
    }

    public boolean isPinned(String dimensionId, int blockX, int blockY, int blockZ) {
        synchronized (lock) {
            return pins.contains(new PinKey(dimensionId, blockX >> 4, blockY >> 4, blockZ >> 4));
        }
    }

    public Status status() {
        HotSectionsConfig cfg = config;
        int pinned = pinCount;
        int auto = autoHot;
        return new Status(pinned, auto, cfg.maxSections, (pinned + auto) * SECTION_BYTES, cfg.maxSections * SECTION_BYTES,
                cfg.autoPromote, promotions.get(), demotions.get(), refusals.get());
    }

    public boolean isHot(String dimensionId, int chunkX, int sectionY, int chunkZ) {
        WDCChunk chunk = chunkOf(dimensionId, chunkX, chunkZ);
        return chunk != null && chunk.isHot(sectionY);
    }

    @Override
    public void onPublished(String dimensionId, int chunkX, int chunkZ, WDCChunk chunk) {
        Integer[] levels;
        synchronized (lock) {
            Set<Integer> set = pinsByChunk.get(new ChunkRef(dimensionId, chunkX, chunkZ));
            if (set == null) return;
            levels = set.toArray(new Integer[0]);
        }
        for (int sectionY : levels) chunk.setPinned(sectionY, true);
    }

    @Override
    public void onCleared() {
        synchronized (lock) {
            pins.clear();
            pinsByChunk.clear();
            pinCount = 0;
        }
        autoHot = 0;
        tickCounter = 0;
    }

    public void tick() {
        if (++tickCounter % WINDOW_TICKS != 0) return;
        scan();
    }

    void scan() {
        HotSectionsConfig cfg = config;
        List<PinKey> current = pins();
        for (PinKey key : current) applyPin(key);
        int[] autoCount = new int[1];
        int[] demoted = new int[1];
        HeatScan.Rules rules = new HeatScan.Rules(
                Math.max(1, cfg.promoteReadsPerTick) * WINDOW_TICKS,
                cfg.maxMutationsPerWindow,
                windowsFor(cfg.promoteAfterTicks),
                windowsFor(cfg.demoteAfterTicks));
        candidates.clear();
        HeatScan.Sink sink = new HeatScan.Sink() {
            @Override
            public void candidate(WDCChunk chunk, int sectionY, int reads) {
                candidates.add(new Candidate(chunk, sectionY, reads));
            }

            @Override
            public void idle(WDCChunk chunk, int sectionY) {
                if (chunk.demoteHot(sectionY)) demoted[0]++;
            }
        };
        cache.forEachEntry((dimensionId, key, freshness, chunk) -> {
            if (!cfg.autoPromote) {
                demoted[0] += chunk.demoteAllUnpinned();
            } else if (freshness == DataFreshness.LIVE) {
                chunk.rollHeat(rules, sink);
            }
            autoCount[0] += chunk.autoHotSectionCount();
        });
        demotions.addAndGet(demoted[0]);
        autoHot = autoCount[0];
        if (!cfg.autoPromote || candidates.isEmpty()) return;
        candidates.sort((a, b) -> Integer.compare(b.reads(), a.reads()));
        int promoted = 0;
        for (Candidate candidate : candidates) {
            if (promoted >= MAX_PROMOTIONS_PER_WINDOW || pinCount + autoHot >= cfg.maxSections) break;
            if (candidate.chunk().promoteHot(candidate.sectionY())) {
                autoHot++;
                promoted++;
                promotions.incrementAndGet();
            }
        }
        candidates.clear();
    }

    private static int windowsFor(int ticks) {
        return Math.max(1, (ticks + WINDOW_TICKS - 1) / WINDOW_TICKS);
    }

    private WDCChunk chunkOf(String dimensionId, int chunkX, int chunkZ) {
        WorldKey world = cache.currentWorldKey(dimensionId);
        WDCCache.EntryView view = cache.inspect(world, chunkX, chunkZ);
        return view == null ? null : view.chunk();
    }

    private void applyPin(PinKey key) {
        WDCChunk chunk = chunkOf(key.dimensionId(), key.chunkX(), key.chunkZ());
        if (chunk == null) return;
        boolean wasAuto = chunk.isHot(key.sectionY()) && !chunk.isPinned(key.sectionY());
        if (chunk.setPinned(key.sectionY(), true) && wasAuto && autoHot > 0) autoHot--;
    }

    private boolean shedOneAuto() {
        boolean[] shed = new boolean[1];
        cache.forEachEntry((dimensionId, key, freshness, chunk) -> {
            if (shed[0]) return;
            int dropped = chunk.demoteAllUnpinned();
            if (dropped > 0) {
                shed[0] = true;
                demotions.addAndGet(dropped);
                autoHot = Math.max(0, autoHot - dropped);
            }
        });
        return shed[0];
    }

    private void shedAllAuto() {
        int[] dropped = new int[1];
        cache.forEachEntry((dimensionId, key, freshness, chunk) -> dropped[0] += chunk.demoteAllUnpinned());
        demotions.addAndGet(dropped[0]);
        autoHot = Math.max(0, autoHot - dropped[0]);
    }
}
