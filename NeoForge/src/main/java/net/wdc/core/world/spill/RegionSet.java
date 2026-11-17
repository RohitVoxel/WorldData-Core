package net.wdc.core.world.spill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class RegionSet {

    public enum Kind { SECTOR, LOG }

    public static final int REGION_SHIFT = 5;
    public static final int MAX_OPEN_REGIONS = 24;
    private static final int MAINTENANCE_EVERY_WRITES = 256;

    public static String regionFileName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".wdr";
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private final Path dir;
    private final String scope;
    private final Kind kind;
    private final LinkedHashMap<Long, RegionStore> open = new LinkedHashMap<>(16, 0.75f, true);
    private int writesSinceMaintenance;
    private int total = -1;

    public RegionSet(Path dir, String scope) {
        this(dir, scope, Kind.SECTOR);
    }

    public RegionSet(Path dir, String scope, Kind kind) {
        this.dir = dir;
        this.scope = scope;
        this.kind = kind;
    }

    private RegionStore openStore(Path path, int rx, int rz, boolean create) throws IOException {
        return kind == Kind.LOG ? LogRegionFile.open(path, scope, create) : RegionFile.open(path, scope, rx, rz, create);
    }

    private static int[] parseRegion(Path path) {
        String name = path.getFileName().toString();
        String[] parts = name.split("\\.");
        if (parts.length != 4 || !parts[0].equals("r")) return null;
        try {
            return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException notARegion) {
            return null;
        }
    }

    private static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    private RegionStore region(int rx, int rz, boolean create) throws IOException {
        long key = regionKey(rx, rz);
        RegionStore file = open.get(key);
        if (file != null) return file;
        file = openStore(dir.resolve(regionFileName(rx, rz)), rx, rz, create);
        if (file == null) return null;
        open.put(key, file);
        while (open.size() > MAX_OPEN_REGIONS) {
            Iterator<Map.Entry<Long, RegionStore>> it = open.entrySet().iterator();
            Map.Entry<Long, RegionStore> eldest = it.next();
            eldest.getValue().close();
            it.remove();
        }
        return file;
    }

    public void put(int x, int z, byte[] payload, long stamp) throws IOException {
        putAt(x >> REGION_SHIFT, z >> REGION_SHIFT, x, z, payload, stamp);
    }

    public void putAt(int regionX, int regionZ, int keyA, int keyB, byte[] payload, long stamp) throws IOException {
        RegionStore file = region(regionX, regionZ, true);
        boolean added = file.put(keyA, keyB, payload, stamp);
        if (added && total >= 0) total++;
        if (++writesSinceMaintenance >= MAINTENANCE_EVERY_WRITES) {
            writesSinceMaintenance = 0;
            file.maintain();
        }
    }

    public byte[] get(int x, int z) throws IOException {
        return getAt(x >> REGION_SHIFT, z >> REGION_SHIFT, x, z);
    }

    public byte[] getAt(int regionX, int regionZ, int keyA, int keyB) throws IOException {
        RegionStore file = region(regionX, regionZ, false);
        return file == null ? null : file.get(keyA, keyB);
    }

    public boolean contains(int x, int z) throws IOException {
        return containsAt(x >> REGION_SHIFT, z >> REGION_SHIFT, x, z);
    }

    public boolean containsAt(int regionX, int regionZ, int keyA, int keyB) throws IOException {
        RegionStore file = region(regionX, regionZ, false);
        return file != null && file.contains(keyA, keyB);
    }

    public void remove(int x, int z, long stamp) throws IOException {
        removeAt(x >> REGION_SHIFT, z >> REGION_SHIFT, x, z, stamp);
    }

    public void removeAt(int regionX, int regionZ, int keyA, int keyB, long stamp) throws IOException {
        RegionStore file = region(regionX, regionZ, false);
        if (file != null && file.remove(keyA, keyB, stamp) && total > 0) total--;
    }

    public void clear() {
        closeAll();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.forEach(RegionSet::deleteQuietly);
            } catch (IOException ignored) {
            }
        }
        total = 0;
    }

    public int regionFileCount() {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> files = Files.list(dir)) {
            return (int) files.filter(p -> p.getFileName().toString().endsWith(".wdr")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    public void closeAll() {
        for (RegionStore file : open.values()) file.close();
        open.clear();
    }

    private List<Path> regionPaths() throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".wdr")).sorted().toList();
        }
    }

    @FunctionalInterface
    public interface EntryConsumer {
        void accept(int x, int z, byte[] payload) throws IOException;
    }

    public void forEach(EntryConsumer consumer) throws IOException {
        for (Path path : regionPaths()) {
            int[] coords = parseRegion(path);
            if (coords == null) continue;
            RegionStore file = region(coords[0], coords[1], false);
            if (file == null) continue;
            for (long[] ks : file.snapshotKeysAndStamps()) {
                int x = (int) (ks[0] >> 32);
                int z = (int) ks[0];
                byte[] payload = file.get(x, z);
                if (payload != null) consumer.accept(x, z, payload);
            }
        }
    }

    public int retain(java.util.function.LongPredicate keep, long stamp) throws IOException {
        int removed = 0;
        for (Path path : regionPaths()) {
            int[] coords = parseRegion(path);
            if (coords == null) continue;
            RegionStore file = region(coords[0], coords[1], false);
            if (file == null) continue;
            for (long[] ks : file.snapshotKeysAndStamps()) {
                if (keep.test(ks[0])) continue;
                if (file.remove((int) (ks[0] >> 32), (int) ks[0], stamp)) removed++;
            }
            file.maintain();
        }
        if (removed > 0 && total >= removed) total -= removed;
        return removed;
    }

    public int countAll() throws IOException {
        closeAll();
        int sum = 0;
        for (Path path : regionPaths()) {
            int[] coords = parseRegion(path);
            if (coords == null) continue;
            RegionStore file = openStore(path, coords[0], coords[1], false);
            if (file == null) continue;
            try {
                sum += file.entryCount();
            } finally {
                file.close();
            }
        }
        total = sum;
        return sum;
    }

    public void prune(int maxChunks, int maxDeletions, long stamp) throws IOException {
        if (total >= 0 && total <= maxChunks) return;
        closeAll();
        List<RegionStore> files = new ArrayList<>();
        try {
            record Candidate(RegionStore file, long key, long stampOf) {}
            List<Candidate> candidates = new ArrayList<>();
            int sum = 0;
            for (Path path : regionPaths()) {
                int[] coords = parseRegion(path);
                if (coords == null) continue;
                RegionStore file = openStore(path, coords[0], coords[1], false);
                if (file == null) continue;
                files.add(file);
                sum += file.entryCount();
                for (long[] ks : file.snapshotKeysAndStamps()) candidates.add(new Candidate(file, ks[0], ks[1]));
            }
            total = sum;
            if (sum <= maxChunks) return;
            candidates.sort(Comparator.comparingLong(Candidate::stampOf));
            int toDelete = Math.min(sum - maxChunks, maxDeletions);
            for (int i = 0; i < toDelete; i++) {
                Candidate c = candidates.get(i);
                if (c.file().remove((int) (c.key() >> 32), (int) c.key(), stamp)) total--;
            }
        } finally {
            for (RegionStore file : files) {
                try {
                    file.maintain();
                } catch (IOException ignored) {
                }
                file.close();
            }
        }
    }
}
