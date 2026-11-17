package net.wdc.core.world.identity;

import net.wdc.core.config.CorePaths;
import net.wdc.core.world.spill.RegionSet;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

final class IdentityFileStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Identity");
    private static final int MAGIC = 0x41544D49;
    private static final int VERSION = 1;
    private static final int COLUMN_VERSION = 2;
    private static final String LEGACY_FILE = "identity.dat";
    private static final String IDENTITY_DIRECTORY = "identity";
    private static final int Y_OFFSET = 2048;

    private final AtomicLong corruptedReadsDetected = new AtomicLong();
    private final Map<String, RegionSet> sets = new ConcurrentHashMap<>();

    IdentityFileStore() {}

    void write(String dimensionKey, List<IdentityPositionSnapshot> positions) {
        RegionSet set = regionSet(dimensionKey);
        if (set == null) return;
        Map<Long, List<IdentityPositionSnapshot>> byChunk = new HashMap<>();
        for (IdentityPositionSnapshot entry : positions) {
            long chunk = chunkKey(entry.pos().getX() >> 4, entry.pos().getZ() >> 4);
            byChunk.computeIfAbsent(chunk, k -> new ArrayList<>()).add(entry);
        }
        long stamp = System.currentTimeMillis();
        try {
            synchronized (set) {
                for (Map.Entry<Long, List<IdentityPositionSnapshot>> column : byChunk.entrySet()) {
                    int cx = (int) (column.getKey() >> 32);
                    int cz = (int) (long) column.getKey();
                    byte[] payload = encodeColumn(cx, cz, column.getValue());
                    byte[] existing = set.get(cx, cz);
                    if (existing != null && Arrays.equals(existing, payload)) continue;
                    set.put(cx, cz, payload, stamp);
                }
                Set<Long> keep = new HashSet<>(byChunk.keySet());
                set.retain(keep::contains, stamp);
            }
            deleteLegacy(dimensionKey);
        } catch (IOException e) {
            LOGGER.debug("WorldData: failed to persist block identity for {} — {}", dimensionKey, e.getMessage());
        }
    }

    Optional<List<IdentityPositionSnapshot>> read(String dimensionKey) {
        RegionSet set = regionSet(dimensionKey);
        if (set == null) return Optional.empty();
        List<IdentityPositionSnapshot> result = new ArrayList<>();
        try {
            synchronized (set) {
                set.forEach((cx, cz, payload) -> decodeColumn(cx, cz, payload, result));
            }
        } catch (IOException | RuntimeException unreadable) {
            corruptedReadsDetected.incrementAndGet();
            LOGGER.debug("WorldData: discarding unreadable identity regions for {}", dimensionKey);
            return Optional.empty();
        }
        if (!result.isEmpty()) return Optional.of(result);
        return readLegacy(dimensionKey);
    }

    long corruptedReadsDetected() {
        return corruptedReadsDetected.get();
    }

    void close() {
        for (RegionSet set : sets.values()) {
            synchronized (set) {
                set.closeAll();
            }
        }
        sets.clear();
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static byte[] encodeColumn(int cx, int cz, List<IdentityPositionSnapshot> entries) throws IOException {
        List<IdentityPositionSnapshot> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> Long.compare(packLocal(a.pos()), packLocal(b.pos())));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8 + sorted.size() * 12);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(COLUMN_VERSION);
            out.writeInt(sorted.size());
            for (IdentityPositionSnapshot entry : sorted) {
                out.writeInt(packLocal(entry.pos()));
                out.writeLong(entry.seed());
            }
        }
        return bytes.toByteArray();
    }

    private static int packLocal(BlockPos pos) {
        return ((pos.getY() + Y_OFFSET) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
    }

    private void decodeColumn(int cx, int cz, byte[] payload, List<IdentityPositionSnapshot> out) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != MAGIC || in.readInt() != COLUMN_VERSION) {
                corruptedReadsDetected.incrementAndGet();
                return;
            }
            int count = in.readInt();
            if (count < 0 || count > payload.length) {
                corruptedReadsDetected.incrementAndGet();
                return;
            }
            for (int i = 0; i < count; i++) {
                int packed = in.readInt();
                long seed = in.readLong();
                int x = (cx << 4) | (packed & 15);
                int z = (cz << 4) | ((packed >> 4) & 15);
                int y = (packed >>> 8) - Y_OFFSET;
                out.add(new IdentityPositionSnapshot(new BlockPos(x, y, z), seed));
            }
        }
    }

    private Optional<List<IdentityPositionSnapshot>> readLegacy(String dimensionKey) {
        Path file = legacyPath(dimensionKey);
        if (file == null || !Files.exists(file)) return Optional.empty();
        try (InputStream fileIn = Files.newInputStream(file);
             GZIPInputStream gzip = new GZIPInputStream(fileIn);
             DataInputStream data = new DataInputStream(gzip)) {
            if (data.readInt() != MAGIC || data.readInt() != VERSION) {
                corruptedReadsDetected.incrementAndGet();
                return Optional.empty();
            }
            int count = data.readInt();
            if (count < 0) {
                corruptedReadsDetected.incrementAndGet();
                return Optional.empty();
            }
            List<IdentityPositionSnapshot> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                BlockPos pos = new BlockPos(data.readInt(), data.readInt(), data.readInt());
                result.add(new IdentityPositionSnapshot(pos, data.readLong()));
            }
            return Optional.of(result);
        } catch (IOException | RuntimeException corruptOrUnreadable) {
            corruptedReadsDetected.incrementAndGet();
            return Optional.empty();
        }
    }

    private void deleteLegacy(String dimensionKey) {
        Path file = legacyPath(dimensionKey);
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    private Path legacyPath(String dimensionKey) {
        if (!CorePaths.isInitialized() || !CorePaths.hasWorld()) return null;
        return CorePaths.dimensionDirectory(dimensionKey).resolve(LEGACY_FILE);
    }

    private RegionSet regionSet(String dimensionKey) {
        if (!CorePaths.isInitialized() || !CorePaths.hasWorld()) return null;
        String worldKey = CorePaths.worldKey();
        return sets.computeIfAbsent(worldKey + "|" + dimensionKey, k -> new RegionSet(
                CorePaths.dimensionDirectory(worldKey, dimensionKey).resolve(IDENTITY_DIRECTORY),
                worldKey + "/" + dimensionKey + "/" + IDENTITY_DIRECTORY));
    }
}
