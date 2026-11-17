package net.wdc.core.api.internal;

import net.wdc.core.api.request.WDCLightInfo;
import net.wdc.core.api.request.WDCLightResult;
import net.wdc.core.debug.WDCMetrics;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

public final class LightSnapshots {

    public static final int FRESH_TICKS = 40;
    public static final int MAX_PENDING = 8192;
    public static final int MAX_BULK = 32768;
    public static final int MAX_CHUNK_PAGES = 16384;
    public static final long MAX_BYTES = 128L << 20;
    public static final int SETTLE_TICKS = 3;

    private static final int PAGE_SHIFT = 5;
    private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    private static final int PAGE_OFFSET = 8;
    private static final int RECENT = 1024;
    private static final int SECTION_OVERHEAD = 40;
    private static final int LAYER_BYTES = 2048;
    private static final Layer[] UNIFORM = newUniform();

    public record SectionKey(String dimensionId, int sectionX, int sectionY, int sectionZ) {}

    public record Clock(float timeOfDay, float rainLevel, float thunderLevel, boolean hasSkyLight) {}

    public static final class Layer {
        private final byte fill;
        private final byte[] nibbles;

        private Layer(byte fill, byte[] nibbles) {
            this.fill = fill;
            this.nibbles = nibbles;
        }

        public static Layer uniform(int value) {
            return UNIFORM[value & 15];
        }

        public static Layer ofValues(byte[] values) {
            if (values == null || values.length != 4096) throw new IllegalArgumentException("values must hold 4096 entries");
            byte first = values[0];
            boolean same = true;
            for (int i = 1; i < 4096; i++) {
                if (values[i] != first) {
                    same = false;
                    break;
                }
            }
            if (same) return uniform(first);
            byte[] packed = new byte[LAYER_BYTES];
            for (int i = 0; i < 4096; i += 2) packed[i >> 1] = (byte) ((values[i] & 15) | ((values[i + 1] & 15) << 4));
            return new Layer((byte) 0, packed);
        }

        public boolean isUniform() {
            return nibbles == null;
        }

        public int get(int index) {
            if (nibbles == null) return fill;
            int b = nibbles[index >> 1];
            return (index & 1) == 0 ? b & 15 : (b >> 4) & 15;
        }

        int bytes() {
            return nibbles == null ? 0 : LAYER_BYTES;
        }
    }

    public static final class Section {
        private final Layer sky;
        private final Layer block;
        private final long tick;
        private final boolean missing;

        private Section(Layer sky, Layer block, long tick, boolean missing) {
            this.sky = sky;
            this.block = block;
            this.tick = tick;
            this.missing = missing;
        }

        public static Section of(Layer sky, Layer block, long tick) {
            if (sky == null || block == null) throw new IllegalArgumentException("layers must not be null");
            return new Section(sky, block, tick, false);
        }

        public static Section missing(long tick) {
            return new Section(UNIFORM[0], UNIFORM[0], tick, true);
        }

        public boolean isMissing() {
            return missing;
        }

        int bytes() {
            return SECTION_OVERHEAD + sky.bytes() + block.bytes();
        }
    }

    private record Published(Clock clock, int darkening) {}

    private static final class ChunkPage {
        final AtomicReferenceArray<Section> sections = new AtomicReferenceArray<>(PAGE_SIZE);
        final String dimensionId;
        final long key;
        volatile boolean released;

        ChunkPage(String dimensionId, long key) {
            this.dimensionId = dimensionId;
            this.key = key;
        }
    }

    private static final class DimLight {
        final ConcurrentHashMap<Long, ChunkPage> pages = new ConcurrentHashMap<>();
        final AtomicReferenceArray<ChunkPage> recent = new AtomicReferenceArray<>(RECENT);
        volatile Published published;

        ChunkPage page(long key) {
            int index = (int) (key ^ (key >>> 21) ^ (key >>> 43)) & (RECENT - 1);
            ChunkPage hit = recent.get(index);
            if (hit != null && hit.key == key && !hit.released) return hit;
            ChunkPage found = pages.get(key);
            if (found != null) recent.lazySet(index, found);
            return found;
        }

        void forget(ChunkPage page) {
            int index = (int) (page.key ^ (page.key >>> 21) ^ (page.key >>> 43)) & (RECENT - 1);
            recent.compareAndSet(index, page, null);
        }
    }

    private record Settle(SectionKey key, long due) {}

    private static final LightSnapshots SHARED = new LightSnapshots();

    private final ConcurrentHashMap<String, DimLight> dimensions = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SectionKey> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<SectionKey, Boolean> pending = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Settle> later = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<SectionKey, Boolean> laterPending = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SectionKey> bulk = new ConcurrentLinkedQueue<>();
    private final AtomicInteger bulkSize = new AtomicInteger();
    private final ConcurrentLinkedQueue<ChunkPage> order = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pageCount = new AtomicInteger();
    private final AtomicInteger orderSize = new AtomicInteger();
    private final LongAdder bytes = new LongAdder();
    private final LongAdder sections = new LongAdder();
    private volatile long tick;
    private volatile long lastMarked = Long.MIN_VALUE;
    private volatile String lastMarkedDimension = "";

    public static LightSnapshots shared() {
        return SHARED;
    }

    public void advance(long gameTick) {
        tick = gameTick;
        lastMarked = Long.MIN_VALUE;
    }

    public long tick() {
        return tick;
    }

    public void publishClock(String dimensionId, Clock clock) {
        DimLight dim = dimensions.computeIfAbsent(dimensionId, id -> new DimLight());
        int darkening = clock.hasSkyLight() ? SkyDarkening.of(clock.timeOfDay(), clock.rainLevel(), clock.thunderLevel()) : 0;
        dim.published = new Published(clock, darkening);
    }

    public Optional<WDCLightInfo> read(String dimensionId, int blockX, int blockY, int blockZ) {
        return readResult(dimensionId, blockX, blockY, blockZ).toOptional();
    }

    public WDCLightResult readResult(String dimensionId, int blockX, int blockY, int blockZ) {
        int sectionY = blockY >> 4;
        DimLight dim = dimensions.get(dimensionId);
        Section section = null;
        if (dim != null) {
            ChunkPage page = dim.page(pageKey(blockX >> 4, blockZ >> 4, sectionY));
            if (page != null) section = page.sections.get(slot(sectionY));
        }
        if (section == null) {
            request(new SectionKey(dimensionId, blockX >> 4, sectionY, blockZ >> 4));
            WDCMetrics.increment(WDCMetrics.Counter.LIGHT_READ_PENDING);
            return WDCLightResult.PENDING;
        }
        long age = tick - section.tick;
        if (age > FRESH_TICKS) {
            request(new SectionKey(dimensionId, blockX >> 4, sectionY, blockZ >> 4));
            if (!section.missing) WDCMetrics.increment(WDCMetrics.Counter.LIGHT_READ_STALE);
        }
        if (section.missing) {
            WDCMetrics.increment(WDCMetrics.Counter.LIGHT_READ_MISSING_SECTION);
            return WDCLightResult.UNLOADED;
        }
        Published published = dim.published;
        if (published == null) {
            WDCMetrics.increment(WDCMetrics.Counter.LIGHT_READ_NO_CLOCK);
            return WDCLightResult.UNAVAILABLE;
        }
        WDCMetrics.increment(WDCMetrics.Counter.LIGHT_READ_HIT);
        int index = ((blockY & 15) << 8) | ((blockZ & 15) << 4) | (blockX & 15);
        int sky = section.sky.get(index);
        int block = section.block.get(index);
        int darkening = published.darkening;
        int effectiveSky = SkyDarkening.effectiveSkyLight(sky, darkening);
        return WDCLightResult.present(new WDCLightInfo(sky, block, darkening, effectiveSky, Math.max(block, effectiveSky)));
    }

    public SectionKey poll() {
        SectionKey key = queue.poll();
        if (key != null) {
            pending.remove(key);
            return key;
        }
        Settle head = later.peek();
        if (head != null && head.due <= tick) {
            later.poll();
            laterPending.remove(head.key);
            return head.key;
        }
        while ((key = bulk.poll()) != null) {
            bulkSize.decrementAndGet();
            Section existing = sectionOf(key);
            if (existing == null || existing.missing) return key;
        }
        return null;
    }

    public boolean hasRequested() {
        return !queue.isEmpty();
    }

    public int pendingCount() {
        return pending.size();
    }

    public int bulkCount() {
        return bulkSize.get();
    }

    public void put(SectionKey key, byte[] packed, long gameTick) {
        if (packed == null) {
            putMissing(key, gameTick);
            return;
        }
        byte[] sky = new byte[4096];
        byte[] block = new byte[4096];
        for (int i = 0; i < 4096; i++) {
            int value = packed[i] & 0xFF;
            sky[i] = (byte) (value >> 4);
            block[i] = (byte) (value & 15);
        }
        putSection(key, Section.of(Layer.ofValues(sky), Layer.ofValues(block), gameTick));
    }

    public void putMissing(SectionKey key, long gameTick) {
        putSection(key, Section.missing(gameTick));
    }

    public void putSection(SectionKey key, Section section) {
        DimLight dim = dimensions.computeIfAbsent(key.dimensionId, id -> new DimLight());
        long pageKey = pageKey(key.sectionX, key.sectionZ, key.sectionY);
        ChunkPage page = dim.page(pageKey);
        if (page == null) {
            ChunkPage created = new ChunkPage(key.dimensionId, pageKey);
            page = dim.pages.putIfAbsent(pageKey, created);
            if (page == null) {
                page = created;
                order.add(created);
                orderSize.incrementAndGet();
                pageCount.incrementAndGet();
                if (orderSize.get() > MAX_CHUNK_PAGES * 2) compactOrder();
            }
        }
        Section old = page.sections.getAndSet(slot(key.sectionY), section);
        bytes.add(section.bytes() - (old == null ? 0 : old.bytes()));
        if (old == null) sections.increment();
        evict();
    }

    public void markBlockChanged(String dimensionId, int blockX, int blockY, int blockZ) {
        DimLight dim = dimensions.get(dimensionId);
        if (dim == null) return;
        int sx = blockX >> 4;
        int sy = blockY >> 4;
        int sz = blockZ >> 4;
        long packed = ((long) sx & 0xFFFFFFL) | (((long) sz & 0xFFFFFFL) << 24) | (((long) sy & 0xFFFFL) << 48);
        if (packed == lastMarked && dimensionId.equals(lastMarkedDimension)) return;
        lastMarked = packed;
        lastMarkedDimension = dimensionId;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) markDirty(dim, dimensionId, sx + dx, sy + dy, sz + dz);
            }
        }
    }

    public void onChunkLoaded(String dimensionId, int chunkX, int chunkZ, int minSection, int maxSection) {
        DimLight dim = dimensions.computeIfAbsent(dimensionId, id -> new DimLight());
        for (int sy = minSection; sy < maxSection; sy++) {
            if (bulkSize.get() >= MAX_BULK) break;
            SectionKey key = new SectionKey(dimensionId, chunkX, sy, chunkZ);
            Section existing = sectionOf(dim, key);
            if (existing == null || existing.missing) {
                bulk.add(key);
                bulkSize.incrementAndGet();
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int sy = minSection; sy < maxSection; sy++) markDirty(dim, dimensionId, chunkX + dx, sy, chunkZ + dz);
            }
        }
    }

    public void dropChunk(String dimensionId, int chunkX, int chunkZ) {
        DimLight dim = dimensions.get(dimensionId);
        if (dim == null) return;
        for (int page = -2; page <= 3; page++) {
            ChunkPage removed = dim.pages.remove(rawPageKey(chunkX, chunkZ, page));
            if (removed != null) release(removed);
        }
    }

    public int cachedSections() {
        return (int) sections.sum();
    }

    public int cachedChunkPages() {
        return pageCount.get();
    }

    public long cachedBytes() {
        return bytes.sum();
    }

    public void clear() {
        dimensions.clear();
        queue.clear();
        pending.clear();
        later.clear();
        laterPending.clear();
        bulk.clear();
        bulkSize.set(0);
        order.clear();
        orderSize.set(0);
        pageCount.set(0);
        bytes.reset();
        sections.reset();
        lastMarked = Long.MIN_VALUE;
        lastMarkedDimension = "";
        tick = 0L;
    }

    private void markDirty(DimLight dim, String dimensionId, int sx, int sy, int sz) {
        Section existing = sectionOf(dim, sx, sy, sz);
        if (existing == null || existing.missing) return;
        SectionKey key = new SectionKey(dimensionId, sx, sy, sz);
        request(key);
        if (laterPending.putIfAbsent(key, Boolean.TRUE) == null) later.add(new Settle(key, tick + SETTLE_TICKS));
    }

    private Section sectionOf(SectionKey key) {
        DimLight dim = dimensions.get(key.dimensionId);
        return dim == null ? null : sectionOf(dim, key);
    }

    private static Section sectionOf(DimLight dim, SectionKey key) {
        return sectionOf(dim, key.sectionX, key.sectionY, key.sectionZ);
    }

    private static Section sectionOf(DimLight dim, int sx, int sy, int sz) {
        ChunkPage page = dim.page(pageKey(sx, sz, sy));
        return page == null ? null : page.sections.get(slot(sy));
    }

    private void request(SectionKey key) {
        if (pending.size() >= MAX_PENDING) {
            WDCMetrics.increment(WDCMetrics.Counter.LIGHT_REQUEST_DROPPED);
            return;
        }
        if (pending.putIfAbsent(key, Boolean.TRUE) == null) queue.add(key);
    }

    private void evict() {
        while (pageCount.get() > MAX_CHUNK_PAGES || bytes.sum() > MAX_BYTES) {
            ChunkPage oldest = order.poll();
            if (oldest == null) return;
            orderSize.decrementAndGet();
            DimLight dim = dimensions.get(oldest.dimensionId);
            if (dim != null && dim.pages.remove(oldest.key, oldest)) release(oldest);
        }
    }

    private void release(ChunkPage page) {
        page.released = true;
        DimLight owner = dimensions.get(page.dimensionId);
        if (owner != null) owner.forget(page);
        long freed = 0L;
        int count = 0;
        for (int i = 0; i < PAGE_SIZE; i++) {
            Section section = page.sections.getAndSet(i, null);
            if (section != null) {
                freed += section.bytes();
                count++;
            }
        }
        bytes.add(-freed);
        sections.add(-count);
        pageCount.decrementAndGet();
    }

    private synchronized void compactOrder() {
        int kept = 0;
        int polled = orderSize.get();
        for (int i = 0; i < polled; i++) {
            ChunkPage page = order.poll();
            if (page == null) break;
            DimLight dim = dimensions.get(page.dimensionId);
            if (dim != null && dim.pages.get(page.key) == page) {
                order.add(page);
                kept++;
            }
        }
        orderSize.set(kept + Math.max(0, orderSize.get() - polled));
    }

    private static int slot(int sectionY) {
        return (sectionY + PAGE_OFFSET) & (PAGE_SIZE - 1);
    }

    private static long pageKey(int sectionX, int sectionZ, int sectionY) {
        return rawPageKey(sectionX, sectionZ, (sectionY + PAGE_OFFSET) >> PAGE_SHIFT);
    }

    private static long rawPageKey(int sectionX, int sectionZ, int page) {
        return ((long) sectionX & 0x3FFFFFFL) | (((long) sectionZ & 0x3FFFFFFL) << 26) | (((long) page & 0xFFFL) << 52);
    }

    private static Layer[] newUniform() {
        Layer[] layers = new Layer[16];
        for (int i = 0; i < 16; i++) layers[i] = new Layer((byte) i, null);
        return layers;
    }
}
