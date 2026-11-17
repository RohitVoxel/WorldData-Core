package net.wdc.core.world.spill;

import net.wdc.core.config.CorePaths;
import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.WDCSection;
import net.wdc.core.world.storage.BlockStateKey;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

public final class ChunkSpillStore {
    private static final int POSITIONS = 4096;
    private static final int MAX_SECTIONS = 1024;
    private static final int MAX_PALETTE = 4096;
    private static final int BIOME_MARKER = 0x4249_4F4D;
    private static final ThreadLocal<char[]> SLOTS = ThreadLocal.withInitial(() -> new char[POSITIONS]);
    private static final ThreadLocal<int[]> TOKENS = ThreadLocal.withInitial(() -> new int[POSITIONS]);

    private final Path root;
    private final LongSupplier clock;
    private final Map<String, RegionSet> scopes = new HashMap<>();

    public ChunkSpillStore(Path root) {
        this(root, System::currentTimeMillis);
    }

    public ChunkSpillStore(Path root, LongSupplier clock) {
        if (root == null) throw new IllegalArgumentException("root must not be null");
        if (clock == null) throw new IllegalArgumentException("clock must not be null");
        this.root = root;
        this.clock = clock;
    }

    public record SpillSnapshot(int chunkX, int chunkZ, byte[] payload) {}

    public static SpillSnapshot capture(WDCChunk chunk, GlobalBlockStateRegistry registry) {
        if (chunk == null || registry == null) throw new IllegalArgumentException("chunk/registry must not be null");
        ByteSink out = new ByteSink(8192);
        char[] slots = SLOTS.get();
        List<WDCSection> sections = new ArrayList<>(chunk.sections());
        int written = 0;
        for (WDCSection section : sections) if (!section.isDefaultAirOnly()) written++;
        out.putInt(written);
        for (WDCSection section : sections) {
            if (section.isDefaultAirOnly()) continue;
            int paletteSize = section.rawEntryLimit();
            out.putInt(section.sectionY());
            out.putInt(paletteSize);
            for (int i = 0; i < paletteSize; i++) putKey(out, registry.keyOf(section.rawEntryId(i)));
            section.copyRawSlots(slots);
            int runCountAt = out.size();
            out.putInt(0);
            int runs = 0;
            int runStart = 0;
            char runSlot = slots[0];
            for (int p = 1; p < POSITIONS; p++) {
                char slot = slots[p];
                if (slot == runSlot) continue;
                out.putShort(p - runStart - 1);
                out.putShort(runSlot);
                runs++;
                runStart = p;
                runSlot = slot;
            }
            out.putShort(POSITIONS - runStart - 1);
            out.putShort(runSlot);
            runs++;
            out.setInt(runCountAt, runs);
        }
        if (chunk.biomePresent()) {
            out.putInt(BIOME_MARKER);
            out.putUtf(chunk.biomeId());
            out.putInt(Float.floatToIntBits(chunk.rawTemperature()));
            out.putInt(chunk.hasPrecipitation() ? 1 : 0);
        }
        return new SpillSnapshot(chunk.chunkX(), chunk.chunkZ(), out.toByteArray());
    }

    private static void putKey(ByteSink out, BlockStateKey key) {
        out.putUtf(key.blockId());
        Map<String, String> props = key.properties();
        out.putInt(props.size());
        for (Map.Entry<String, String> e : props.entrySet()) {
            out.putUtf(e.getKey());
            out.putUtf(e.getValue());
        }
    }

    private static final class ByteSink {
        private byte[] data;
        private int size;

        ByteSink(int capacity) {
            data = new byte[capacity];
        }

        int size() { return size; }

        private void ensure(int extra) {
            if (size + extra > data.length) data = java.util.Arrays.copyOf(data, Math.max(data.length * 2, size + extra));
        }

        void putInt(int v) {
            ensure(4);
            data[size++] = (byte) (v >>> 24);
            data[size++] = (byte) (v >>> 16);
            data[size++] = (byte) (v >>> 8);
            data[size++] = (byte) v;
        }

        void setInt(int at, int v) {
            data[at] = (byte) (v >>> 24);
            data[at + 1] = (byte) (v >>> 16);
            data[at + 2] = (byte) (v >>> 8);
            data[at + 3] = (byte) v;
        }

        void putShort(int v) {
            ensure(2);
            data[size++] = (byte) (v >>> 8);
            data[size++] = (byte) v;
        }

        void putUtf(String value) {
            int length = value.length();
            boolean ascii = length < 65535;
            for (int i = 0; i < length && ascii; i++) {
                char c = value.charAt(i);
                if (c == 0 || c > 0x7F) ascii = false;
            }
            if (ascii) {
                ensure(2 + length);
                data[size++] = (byte) (length >>> 8);
                data[size++] = (byte) length;
                for (int i = 0; i < length; i++) data[size++] = (byte) value.charAt(i);
                return;
            }
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(length + 8);
                new DataOutputStream(bytes).writeUTF(value);
                byte[] encoded = bytes.toByteArray();
                ensure(encoded.length);
                System.arraycopy(encoded, 0, data, size, encoded.length);
                size += encoded.length;
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        byte[] toByteArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }

    public void write(SpillSnapshot snapshot, String scope) {
        try {
            RegionSet store = scopeStore(scope);
            synchronized (store) {
                store.put(snapshot.chunkX(), snapshot.chunkZ(), snapshot.payload(), clock.getAsLong());
            }
        } catch (IOException failed) {
            throw new RuntimeException("Failed to spill chunk " + snapshot.chunkX() + "," + snapshot.chunkZ(), failed);
        }
    }

    public WDCChunk read(String scope, int chunkX, int chunkZ, GlobalBlockStateRegistry registry) {
        RegionSet store = scopeStore(scope);
        byte[] payload;
        try {
            synchronized (store) {
                payload = store.get(chunkX, chunkZ);
            }
        } catch (IOException unreadable) {
            return null;
        }
        if (payload == null) return null;
        try {
            return decode(payload, chunkX, chunkZ, registry);
        } catch (IOException | RuntimeException corrupt) {
            try {
                synchronized (store) {
                    store.remove(chunkX, chunkZ, clock.getAsLong());
                }
            } catch (IOException ignored) {
            }
            return null;
        }
    }

    private static WDCChunk decode(byte[] payload, int chunkX, int chunkZ, GlobalBlockStateRegistry registry) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        WDCChunk chunk = new WDCChunk(chunkX, chunkZ);
        int sectionCount = in.readInt();
        if (sectionCount < 0 || sectionCount > MAX_SECTIONS) throw new IOException("bad section count");
        for (int s = 0; s < sectionCount; s++) {
            int sectionY = in.readInt();
            int paletteSize = in.readInt();
            if (paletteSize < 1 || paletteSize > MAX_PALETTE) throw new IOException("bad palette size");
            int[] paletteTokens = new int[paletteSize + 1];
            for (int i = 0; i < paletteSize; i++) paletteTokens[i + 1] = registry.tokenOf(registry.registerOrGet(readKey(in)));
            int runs = in.readInt();
            if (runs < 1 || runs > POSITIONS) throw new IOException("bad run count");
            int[] tokens = TOKENS.get();
            int cursor = 0;
            for (int r = 0; r < runs; r++) {
                int length = in.readUnsignedShort() + 1;
                int slot = in.readUnsignedShort();
                if (slot > paletteSize || cursor + length > POSITIONS) throw new IOException("bad run");
                java.util.Arrays.fill(tokens, cursor, cursor + length, paletteTokens[slot]);
                cursor += length;
            }
            if (cursor != POSITIONS) throw new IOException("incomplete section");
            chunk.installTokens(sectionY, tokens);
        }
        if (in.available() >= 4) {
            if (in.readInt() == BIOME_MARKER) {
                String biomeId = in.readUTF();
                float temperature = Float.intBitsToFloat(in.readInt());
                boolean precipitation = in.readInt() != 0;
                chunk.setBiomeSample(biomeId, temperature, precipitation);
            }
        }
        chunk.publishHeights(registry);
        chunk.primeContentHashes();
        return chunk;
    }

    public boolean exists(String scope, int chunkX, int chunkZ) {
        RegionSet store = scopeStore(scope);
        try {
            synchronized (store) {
                return store.contains(chunkX, chunkZ);
            }
        } catch (IOException unreadable) {
            return false;
        }
    }

    public void prune(String scope, int maxChunks) {
        prune(scope, maxChunks, Integer.MAX_VALUE);
    }

    public void prune(String scope, int maxChunks, int maxDeletions) {
        RegionSet store = scopeStore(scope);
        try {
            synchronized (store) {
                store.prune(maxChunks, maxDeletions, clock.getAsLong());
            }
        } catch (IOException ignored) {
        }
    }

    public void delete(String scope, int chunkX, int chunkZ) {
        RegionSet store = scopeStore(scope);
        try {
            synchronized (store) {
                store.remove(chunkX, chunkZ, clock.getAsLong());
            }
        } catch (IOException ignored) {
        }
    }

    public void clear(String scope) {
        RegionSet store = scopeStore(scope);
        synchronized (store) {
            store.clear();
        }
    }

    public int regionFileCount(String scope) {
        RegionSet store = scopeStore(scope);
        synchronized (store) {
            return store.regionFileCount();
        }
    }

    public int entryCount(String scope) {
        RegionSet store = scopeStore(scope);
        try {
            synchronized (store) {
                return store.countAll();
            }
        } catch (IOException unreadable) {
            return 0;
        }
    }

    public Path regionDirectory(String scope) {
        return regionDir(scope);
    }

    public void close() {
        List<RegionSet> open;
        synchronized (scopes) {
            open = new ArrayList<>(scopes.values());
            scopes.clear();
        }
        for (RegionSet store : open) {
            synchronized (store) {
                store.closeAll();
            }
        }
    }

    private RegionSet scopeStore(String scope) {
        synchronized (scopes) {
            return scopes.computeIfAbsent(scope, s -> new RegionSet(regionDir(s), s));
        }
    }

    private Path regionDir(String scope) {
        Path p = root;
        for (String segment : scope.split("/")) {
            p = p.resolve(CorePaths.safeName(segment));
        }
        return p.resolve(CorePaths.REGION_DIRECTORY);
    }

    static String regionFileName(int regionX, int regionZ) {
        return RegionSet.regionFileName(regionX, regionZ);
    }

    private static BlockStateKey readKey(DataInputStream in) throws IOException {
        String blockId = in.readUTF();
        int propCount = in.readInt();
        if (propCount == 0) return BlockStateKey.of(blockId);
        Map<String, String> props = new java.util.TreeMap<>();
        for (int i = 0; i < propCount; i++) {
            props.put(in.readUTF(), in.readUTF());
        }
        return BlockStateKey.of(blockId, props);
    }

    private static void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
    }
}
