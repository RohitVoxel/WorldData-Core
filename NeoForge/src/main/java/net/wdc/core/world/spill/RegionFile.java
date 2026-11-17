package net.wdc.core.world.spill;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.CRC32;

final class RegionFile implements RegionStore {

    static final int MAGIC = 0x57445232;
    static final int VERSION = 2;
    static final int LEGACY_LOG_MAGIC = 0x57445247;
    static final int SECTOR_BYTES = 4096;
    static final int SLOT_COUNT = 1024;
    static final int SLOT_BYTES = 16;
    static final int TABLE_START = SECTOR_BYTES;
    static final int TABLE_SECTORS = SLOT_COUNT * SLOT_BYTES / SECTOR_BYTES;
    static final int DATA_START_SECTOR = 1 + TABLE_SECTORS;
    static final int RECORD_HEADER_BYTES = 8;
    static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    static final long DEFRAG_MIN_FILE_BYTES = 2L * 1024L * 1024L;

    private final Path path;
    private final String scope;
    private final int regionX;
    private final int regionZ;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final int[] offsetSector = new int[SLOT_COUNT];
    private final int[] lengthBytes = new int[SLOT_COUNT];
    private final long[] stamp = new long[SLOT_COUNT];
    private final BitSet usedSectors = new BitSet();
    private FileChannel channel;
    private int endSector = DATA_START_SECTOR;
    private int entries;
    private boolean damaged;
    private boolean closed;

    private RegionFile(Path path, String scope, int regionX, int regionZ) {
        this.path = path;
        this.scope = scope;
        this.regionX = regionX;
        this.regionZ = regionZ;
        usedSectors.set(0, DATA_START_SECTOR);
    }

    static RegionFile open(Path path, String scope, int regionX, int regionZ, boolean create) throws IOException {
        RegionFile file = new RegionFile(path, scope, regionX, regionZ);
        if (Files.exists(path)) {
            int kind = file.probe();
            if (kind == 2 && file.loadTable()) return file;
            file.closeQuietly();
            if (kind == 1) {
                RegionFile migrated = migrateLegacyLog(path, scope, regionX, regionZ);
                if (migrated != null) return migrated;
            }
            file.quarantine();
            if (!create) return null;
            file.createNew();
            return file;
        }
        if (!create) return null;
        file.createNew();
        return file;
    }

    static int slotOf(int x, int z) {
        return (x & 31) + ((z & 31) << 5);
    }

    Path path() {
        return path;
    }

    private int probe() throws IOException {
        channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (channel.size() < 8) return 0;
        ByteBuffer head = ByteBuffer.allocate(8);
        readFully(head, 0L);
        head.flip();
        int magic = head.getInt();
        int version = head.getInt();
        if (magic == MAGIC && version == VERSION) return 2;
        if (magic == LEGACY_LOG_MAGIC && version == 1) return 1;
        return 0;
    }

    private byte[] headerPage() {
        byte[] scopeBytes = scope.getBytes(StandardCharsets.UTF_8);
        int keep = Math.min(scopeBytes.length, 512);
        ByteBuffer buffer = ByteBuffer.allocate(SECTOR_BYTES);
        buffer.putInt(MAGIC);
        buffer.putInt(VERSION);
        buffer.putInt(regionX);
        buffer.putInt(regionZ);
        buffer.putLong(scopeHash(scope));
        buffer.putShort((short) keep);
        buffer.put(scopeBytes, 0, keep);
        return buffer.array();
    }

    private static long scopeHash(String scope) {
        long h = 1125899906842597L;
        for (int i = 0; i < scope.length(); i++) h = 31L * h + scope.charAt(i);
        return h;
    }

    private void createNew() throws IOException {
        Files.createDirectories(path.getParent());
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        writeFully(ByteBuffer.wrap(headerPage()), 0L);
        writeFully(ByteBuffer.allocate(SLOT_COUNT * SLOT_BYTES), TABLE_START);
        resetMemory();
    }

    private void resetMemory() {
        java.util.Arrays.fill(offsetSector, 0);
        java.util.Arrays.fill(lengthBytes, 0);
        java.util.Arrays.fill(stamp, 0L);
        usedSectors.clear();
        usedSectors.set(0, DATA_START_SECTOR);
        endSector = DATA_START_SECTOR;
        entries = 0;
    }

    private boolean loadTable() throws IOException {
        long size = channel.size();
        if (size < (long) DATA_START_SECTOR * SECTOR_BYTES) return false;
        ByteBuffer page = ByteBuffer.allocate(SECTOR_BYTES);
        readFully(page, 0L);
        page.flip();
        if (page.getInt() != MAGIC || page.getInt() != VERSION) return false;
        page.getInt();
        page.getInt();
        if (page.getLong() != scopeHash(scope)) return false;
        ByteBuffer table = ByteBuffer.allocate(SLOT_COUNT * SLOT_BYTES);
        readFully(table, TABLE_START);
        table.flip();
        long sectorsInFile = (size + SECTOR_BYTES - 1) / SECTOR_BYTES;
        resetMemory();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            int offset = table.getInt();
            int length = table.getInt();
            long entryStamp = table.getLong();
            if (offset == 0) continue;
            int sectors = sectorsFor(length);
            boolean sane = offset >= DATA_START_SECTOR && length >= 0 && length <= MAX_PAYLOAD_BYTES
                    && (long) offset + sectors <= sectorsInFile;
            if (sane) {
                for (int s = offset; s < offset + sectors; s++) {
                    if (usedSectors.get(s)) {
                        sane = false;
                        break;
                    }
                }
            }
            if (!sane) {
                damaged = true;
                continue;
            }
            usedSectors.set(offset, offset + sectors);
            offsetSector[slot] = offset;
            lengthBytes[slot] = length;
            stamp[slot] = entryStamp;
            endSector = Math.max(endSector, offset + sectors);
            entries++;
        }
        return true;
    }

    private static int sectorsFor(int payloadLength) {
        return (int) (((long) RECORD_HEADER_BYTES + payloadLength + SECTOR_BYTES - 1) / SECTOR_BYTES);
    }

    private void quarantine() {
        closeQuietly();
        resetMemory();
        damaged = true;
        Path aside = path.resolveSibling(path.getFileName() + ".corrupt");
        try {
            Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException alsoIgnored) {
            }
        }
    }

    private static RegionFile migrateLegacyLog(Path path, String scope, int regionX, int regionZ) throws IOException {
        LogRegionFile legacy = LogRegionFile.open(path, scope, false);
        if (legacy == null) return null;
        Path tmp = path.resolveSibling(path.getFileName() + ".migrating");
        RegionFile created = null;
        try {
            Files.deleteIfExists(tmp);
            created = new RegionFile(tmp, scope, regionX, regionZ);
            created.createNew();
            for (long[] ks : legacy.snapshotKeysAndStamps()) {
                int x = LogRegionFile.unpackX(ks[0]);
                int z = LogRegionFile.unpackZ(ks[0]);
                if ((x >> 5) != regionX || (z >> 5) != regionZ) continue;
                byte[] payload = legacy.get(x, z);
                if (payload != null) created.put(x, z, payload, ks[1]);
            }
            created.close();
            legacy.close();
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException notSupported) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            RegionFile reopened = new RegionFile(path, scope, regionX, regionZ);
            reopened.channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            if (!reopened.loadTable()) {
                reopened.closeQuietly();
                return null;
            }
            return reopened;
        } catch (IOException | RuntimeException failed) {
            if (created != null) created.close();
            legacy.close();
            Files.deleteIfExists(tmp);
            return null;
        }
    }

    @Override
    public boolean wasDamaged() {
        lock.readLock().lock();
        try {
            return damaged;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int entryCount() {
        lock.readLock().lock();
        try {
            return entries;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public long fileBytes() {
        lock.readLock().lock();
        try {
            return (long) endSector * SECTOR_BYTES;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public long deadBytes() {
        lock.readLock().lock();
        try {
            return (long) (endSector - DATA_START_SECTOR - usedSectors.get(DATA_START_SECTOR, Math.max(endSector, DATA_START_SECTOR)).cardinality())
                    * SECTOR_BYTES;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean contains(int x, int z) {
        lock.readLock().lock();
        try {
            return offsetSector[slotOf(x, z)] != 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public long stampOf(int x, int z) {
        lock.readLock().lock();
        try {
            int slot = slotOf(x, z);
            return offsetSector[slot] == 0 ? -1L : stamp[slot];
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<long[]> snapshotKeysAndStamps() {
        lock.readLock().lock();
        try {
            List<long[]> out = new ArrayList<>(entries);
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                if (offsetSector[slot] == 0) continue;
                int x = (regionX << 5) + (slot & 31);
                int z = (regionZ << 5) + (slot >> 5);
                out.add(new long[] {((long) x << 32) | (z & 0xFFFFFFFFL), stamp[slot]});
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public byte[] get(int x, int z) throws IOException {
        int slot = slotOf(x, z);
        int offset;
        int length;
        long entryStamp;
        lock.readLock().lock();
        try {
            offset = offsetSector[slot];
            if (offset == 0 || closed) return null;
            length = lengthBytes[slot];
            entryStamp = stamp[slot];
            ByteBuffer buffer = ByteBuffer.allocate(RECORD_HEADER_BYTES + length);
            readFully(buffer, (long) offset * SECTOR_BYTES);
            buffer.flip();
            int storedCrc = buffer.getInt();
            int storedLength = buffer.getInt();
            byte[] payload = new byte[length];
            if (storedLength == length) buffer.get(payload);
            if (storedLength == length && storedCrc == crcOf(slot, entryStamp, length, payload)) return payload;
        } finally {
            lock.readLock().unlock();
        }
        lock.writeLock().lock();
        try {
            if (offsetSector[slot] == offset) dropSlot(slot);
            damaged = true;
        } finally {
            lock.writeLock().unlock();
        }
        return null;
    }

    @Override
    public boolean put(int x, int z, byte[] payload, long entryStamp) throws IOException {
        if (payload == null) throw new IllegalArgumentException("payload must not be null");
        if (payload.length > MAX_PAYLOAD_BYTES) throw new IOException("payload too large: " + payload.length);
        int slot = slotOf(x, z);
        lock.writeLock().lock();
        try {
            if (closed) throw new IOException("region file closed");
            int sectors = sectorsFor(payload.length);
            int offset = allocate(sectors);
            ByteBuffer record = ByteBuffer.allocate(sectors * SECTOR_BYTES);
            record.putInt(crcOf(slot, entryStamp, payload.length, payload));
            record.putInt(payload.length);
            record.put(payload);
            record.rewind();
            writeFully(record, (long) offset * SECTOR_BYTES);
            usedSectors.set(offset, offset + sectors);
            int previousOffset = offsetSector[slot];
            int previousLength = lengthBytes[slot];
            writeSlot(slot, offset, payload.length, entryStamp);
            if (previousOffset != 0) {
                int previousSectors = sectorsFor(previousLength);
                usedSectors.clear(previousOffset, previousOffset + previousSectors);
            } else {
                entries++;
            }
            offsetSector[slot] = offset;
            lengthBytes[slot] = payload.length;
            stamp[slot] = entryStamp;
            endSector = Math.max(endSector, offset + sectors);
            return previousOffset == 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean remove(int x, int z, long entryStamp) throws IOException {
        int slot = slotOf(x, z);
        lock.writeLock().lock();
        try {
            if (closed || offsetSector[slot] == 0) return false;
            writeSlot(slot, 0, 0, 0L);
            dropSlot(slot);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void dropSlot(int slot) {
        int offset = offsetSector[slot];
        if (offset == 0) return;
        usedSectors.clear(offset, offset + sectorsFor(lengthBytes[slot]));
        offsetSector[slot] = 0;
        lengthBytes[slot] = 0;
        stamp[slot] = 0L;
        entries--;
    }

    private int allocate(int sectors) {
        int from = DATA_START_SECTOR;
        while (true) {
            int start = usedSectors.nextClearBit(from);
            int nextUsed = usedSectors.nextSetBit(start);
            if (nextUsed < 0 || nextUsed - start >= sectors) return start;
            from = nextUsed;
        }
    }

    private void writeSlot(int slot, int offset, int length, long entryStamp) throws IOException {
        ByteBuffer entry = ByteBuffer.allocate(SLOT_BYTES);
        entry.putInt(offset);
        entry.putInt(length);
        entry.putLong(entryStamp);
        entry.flip();
        writeFully(entry, TABLE_START + (long) slot * SLOT_BYTES);
    }

    @Override
    public void maintain() throws IOException {
        lock.writeLock().lock();
        try {
            if (closed) return;
            long total = (long) endSector * SECTOR_BYTES;
            long dead = (long) (endSector - DATA_START_SECTOR) * SECTOR_BYTES
                    - (long) usedSectors.get(DATA_START_SECTOR, Math.max(endSector, DATA_START_SECTOR)).cardinality() * SECTOR_BYTES;
            if (total >= DEFRAG_MIN_FILE_BYTES && dead * 2 > total) defragment();
            else trimTail();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void trimTail() throws IOException {
        int last = usedSectors.previousSetBit(Math.max(endSector, DATA_START_SECTOR) - 1);
        int newEnd = Math.max(DATA_START_SECTOR, last + 1);
        if (newEnd < endSector) {
            channel.truncate((long) newEnd * SECTOR_BYTES);
            endSector = newEnd;
        }
    }

    private void defragment() throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        RegionFile packed = new RegionFile(tmp, scope, regionX, regionZ);
        try {
            packed.createNew();
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                if (offsetSector[slot] == 0) continue;
                int x = (regionX << 5) + (slot & 31);
                int z = (regionZ << 5) + (slot >> 5);
                lock.readLock().lock();
                byte[] payload;
                try {
                    payload = readPayloadUnchecked(slot);
                } finally {
                    lock.readLock().unlock();
                }
                if (payload != null) packed.put(x, z, payload, stamp[slot]);
            }
            packed.close();
        } catch (IOException | RuntimeException failed) {
            packed.close();
            Files.deleteIfExists(tmp);
            throw failed instanceof IOException io ? io : new IOException(failed);
        }
        channel.close();
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException notSupported) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (!loadTable()) throw new IOException("defragmented region unreadable");
    }

    private byte[] readPayloadUnchecked(int slot) throws IOException {
        int offset = offsetSector[slot];
        int length = lengthBytes[slot];
        ByteBuffer buffer = ByteBuffer.allocate(RECORD_HEADER_BYTES + length);
        readFully(buffer, (long) offset * SECTOR_BYTES);
        buffer.flip();
        int storedCrc = buffer.getInt();
        int storedLength = buffer.getInt();
        if (storedLength != length) return null;
        byte[] payload = new byte[length];
        buffer.get(payload);
        return storedCrc == crcOf(slot, stamp[slot], length, payload) ? payload : null;
    }

    private static int crcOf(int slot, long entryStamp, int length, byte[] payload) {
        CRC32 crc = new CRC32();
        ByteBuffer head = ByteBuffer.allocate(16);
        head.putInt(slot);
        head.putLong(entryStamp);
        head.putInt(length);
        crc.update(head.array(), 0, 16);
        crc.update(payload, 0, length);
        return (int) crc.getValue();
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) return;
            try {
                if (channel != null && channel.isOpen()) trimTail();
            } catch (IOException ignored) {
            }
            closed = true;
            closeQuietly();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void closeQuietly() {
        try {
            if (channel != null) channel.close();
        } catch (IOException ignored) {
        }
        channel = null;
    }

    private void readFully(ByteBuffer buffer, long position) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, at);
            if (read < 0) throw new IOException("unexpected end of region file");
            at += read;
        }
    }

    private void writeFully(ByteBuffer buffer, long position) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) at += channel.write(buffer, at);
    }
}
