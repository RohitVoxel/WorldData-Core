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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

final class LogRegionFile implements RegionStore {

    static final int FILE_MAGIC = 0x57445247;
    static final int FORMAT_VERSION = 1;
    static final int RECORD_MARKER = 0x57445243;
    static final int RECORD_HEADER_BYTES = 24;
    static final int RECORD_TRAILER_BYTES = 4;
    static final int TOMBSTONE = -1;
    static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;
    static final long COMPACT_MIN_DEAD_BYTES = 256L * 1024L;
    private static final int RESYNC_BLOCK = 64 * 1024;

    record Entry(long payloadOffset, int length, long stamp) {
        long recordBytes() {
            return RECORD_HEADER_BYTES + (long) length + RECORD_TRAILER_BYTES;
        }
    }

    record Key(int x, int z) {
        long packed() {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }
    }

    private final Path path;
    private final String scope;
    private final Map<Long, Entry> index = new HashMap<>();
    private FileChannel channel;
    private long endOffset;
    private long headerBytes;
    private long liveBytes;
    private long deadBytes;
    private boolean damaged;

    private LogRegionFile(Path path, String scope) {
        this.path = path;
        this.scope = scope;
    }

    static LogRegionFile open(Path path, String scope, boolean create) throws IOException {
        LogRegionFile file = new LogRegionFile(path, scope);
        if (Files.exists(path)) {
            if (!file.scanExisting()) {
                file.quarantine();
                if (!create) return null;
                file.createNew();
            }
        } else {
            if (!create) return null;
            file.createNew();
        }
        return file;
    }

    Path path() {
        return path;
    }

    public synchronized boolean wasDamaged() {
        return damaged;
    }

    public synchronized int entryCount() {
        return index.size();
    }

    public synchronized long fileBytes() {
        return endOffset;
    }

    public synchronized long deadBytes() {
        return deadBytes;
    }

    public synchronized boolean contains(int x, int z) {
        return index.containsKey(new Key(x, z).packed());
    }

    public synchronized long stampOf(int x, int z) {
        Entry entry = index.get(new Key(x, z).packed());
        return entry == null ? -1L : entry.stamp();
    }

    public synchronized List<long[]> snapshotKeysAndStamps() {
        List<long[]> out = new ArrayList<>(index.size());
        for (Map.Entry<Long, Entry> e : index.entrySet()) out.add(new long[] {e.getKey(), e.getValue().stamp()});
        return out;
    }

    static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    static int unpackZ(long packed) {
        return (int) packed;
    }

    private byte[] headerBytesFor() {
        byte[] scopeBytes = scope.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 2 + scopeBytes.length);
        buffer.putInt(FILE_MAGIC);
        buffer.putInt(FORMAT_VERSION);
        buffer.putShort((short) scopeBytes.length);
        buffer.put(scopeBytes);
        return buffer.array();
    }

    private void createNew() throws IOException {
        Files.createDirectories(path.getParent());
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        byte[] header = headerBytesFor();
        writeFully(ByteBuffer.wrap(header), 0L);
        headerBytes = header.length;
        endOffset = headerBytes;
        index.clear();
        liveBytes = 0L;
        deadBytes = 0L;
    }

    private void quarantine() {
        closeQuietly();
        index.clear();
        liveBytes = 0L;
        deadBytes = 0L;
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

    private boolean scanExisting() throws IOException {
        channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long size = channel.size();
        byte[] expected = headerBytesFor();
        if (size < expected.length) return false;
        ByteBuffer header = ByteBuffer.allocate(expected.length);
        readFully(header, 0L);
        if (!java.util.Arrays.equals(header.array(), expected)) return false;
        headerBytes = expected.length;
        long position = headerBytes;
        long validEnd = position;
        ByteBuffer head = ByteBuffer.allocate(RECORD_HEADER_BYTES);
        while (position + RECORD_HEADER_BYTES <= size) {
            head.clear();
            readFully(head, position);
            head.flip();
            int marker = head.getInt();
            int length = head.getInt();
            int x = head.getInt();
            int z = head.getInt();
            long stamp = head.getLong();
            boolean tombstone = length == TOMBSTONE;
            boolean sane = marker == RECORD_MARKER && (tombstone || (length >= 0 && length <= MAX_PAYLOAD_BYTES));
            long total = RECORD_HEADER_BYTES + (tombstone ? 0 : length) + RECORD_TRAILER_BYTES;
            if (!sane || position + total > size) {
                damaged = true;
                long next = resync(position + 1, size);
                if (next < 0) break;
                position = next;
                continue;
            }
            long key = new Key(x, z).packed();
            Entry previous = index.remove(key);
            if (previous != null) {
                liveBytes -= previous.recordBytes();
                deadBytes += previous.recordBytes();
            }
            if (tombstone) {
                deadBytes += total;
            } else {
                Entry entry = new Entry(position + RECORD_HEADER_BYTES, length, stamp);
                index.put(key, entry);
                liveBytes += entry.recordBytes();
            }
            position += total;
            validEnd = position;
        }
        endOffset = validEnd;
        if (validEnd < size) {
            damaged = damaged || size - validEnd > 0;
            channel.truncate(validEnd);
        }
        return true;
    }

    private long resync(long from, long size) throws IOException {
        byte[] marker = ByteBuffer.allocate(4).putInt(RECORD_MARKER).array();
        long position = from;
        ByteBuffer block = ByteBuffer.allocate(RESYNC_BLOCK + 3);
        while (position + RECORD_HEADER_BYTES <= size) {
            block.clear();
            int want = (int) Math.min(block.capacity(), size - position);
            block.limit(want);
            readFully(block, position);
            byte[] bytes = block.array();
            for (int i = 0; i + 4 <= want; i++) {
                if (bytes[i] == marker[0] && bytes[i + 1] == marker[1] && bytes[i + 2] == marker[2] && bytes[i + 3] == marker[3]) {
                    return position + i;
                }
            }
            if (want < 4) break;
            position += want - 3;
        }
        return -1L;
    }

    public synchronized byte[] get(int x, int z) throws IOException {
        Entry entry = index.get(new Key(x, z).packed());
        if (entry == null) return null;
        ByteBuffer buffer = ByteBuffer.allocate(entry.length() + RECORD_TRAILER_BYTES);
        readFully(buffer, entry.payloadOffset());
        byte[] payload = new byte[entry.length()];
        buffer.flip();
        buffer.get(payload);
        int stored = buffer.getInt();
        if (stored != crcOf(x, z, entry.stamp(), entry.length(), payload)) {
            index.remove(new Key(x, z).packed());
            liveBytes -= entry.recordBytes();
            deadBytes += entry.recordBytes();
            damaged = true;
            return null;
        }
        return payload;
    }

    public synchronized boolean put(int x, int z, byte[] payload, long stamp) throws IOException {
        if (payload == null) throw new IllegalArgumentException("payload must not be null");
        if (payload.length > MAX_PAYLOAD_BYTES) throw new IOException("payload too large: " + payload.length);
        long key = new Key(x, z).packed();
        ByteBuffer record = ByteBuffer.allocate(RECORD_HEADER_BYTES + payload.length + RECORD_TRAILER_BYTES);
        record.putInt(RECORD_MARKER);
        record.putInt(payload.length);
        record.putInt(x);
        record.putInt(z);
        record.putLong(stamp);
        record.put(payload);
        record.putInt(crcOf(x, z, stamp, payload.length, payload));
        record.flip();
        long offset = endOffset;
        writeFully(record, offset);
        endOffset = offset + RECORD_HEADER_BYTES + payload.length + RECORD_TRAILER_BYTES;
        Entry previous = index.put(key, new Entry(offset + RECORD_HEADER_BYTES, payload.length, stamp));
        Entry added = index.get(key);
        liveBytes += added.recordBytes();
        if (previous != null) {
            liveBytes -= previous.recordBytes();
            deadBytes += previous.recordBytes();
        }
        compactIfWorthwhile();
        return previous == null;
    }

    public synchronized boolean remove(int x, int z, long stamp) throws IOException {
        long key = new Key(x, z).packed();
        Entry previous = index.remove(key);
        if (previous == null) return false;
        ByteBuffer record = ByteBuffer.allocate(RECORD_HEADER_BYTES + RECORD_TRAILER_BYTES);
        record.putInt(RECORD_MARKER);
        record.putInt(TOMBSTONE);
        record.putInt(x);
        record.putInt(z);
        record.putLong(stamp);
        record.putInt(crcOf(x, z, stamp, TOMBSTONE, new byte[0]));
        record.flip();
        writeFully(record, endOffset);
        endOffset += RECORD_HEADER_BYTES + RECORD_TRAILER_BYTES;
        liveBytes -= previous.recordBytes();
        deadBytes += previous.recordBytes() + RECORD_HEADER_BYTES + RECORD_TRAILER_BYTES;
        compactIfWorthwhile();
        return true;
    }

    @Override
    public synchronized void maintain() throws IOException {
        compactIfWorthwhile();
    }

    synchronized void compactIfWorthwhile() throws IOException {
        if (deadBytes >= COMPACT_MIN_DEAD_BYTES && deadBytes > liveBytes) compact();
    }

    synchronized void compact() throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Map<Long, Entry> rebuilt = new HashMap<>();
        long position;
        try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] header = headerBytesFor();
            out.write(ByteBuffer.wrap(header), 0L);
            position = header.length;
            List<Map.Entry<Long, Entry>> ordered = new ArrayList<>(index.entrySet());
            ordered.sort((a, b) -> Long.compare(a.getValue().payloadOffset(), b.getValue().payloadOffset()));
            for (Map.Entry<Long, Entry> e : ordered) {
                int x = unpackX(e.getKey());
                int z = unpackZ(e.getKey());
                Entry old = e.getValue();
                ByteBuffer payload = ByteBuffer.allocate(old.length() + RECORD_TRAILER_BYTES);
                readFully(payload, old.payloadOffset());
                payload.flip();
                byte[] bytes = new byte[old.length()];
                payload.get(bytes);
                int stored = payload.getInt();
                if (stored != crcOf(x, z, old.stamp(), old.length(), bytes)) {
                    damaged = true;
                    continue;
                }
                ByteBuffer record = ByteBuffer.allocate(RECORD_HEADER_BYTES + bytes.length + RECORD_TRAILER_BYTES);
                record.putInt(RECORD_MARKER);
                record.putInt(bytes.length);
                record.putInt(x);
                record.putInt(z);
                record.putLong(old.stamp());
                record.put(bytes);
                record.putInt(stored);
                record.flip();
                long written = 0;
                while (record.hasRemaining()) written += out.write(record, position + written);
                rebuilt.put(e.getKey(), new Entry(position + RECORD_HEADER_BYTES, bytes.length, old.stamp()));
                position += RECORD_HEADER_BYTES + (long) bytes.length + RECORD_TRAILER_BYTES;
            }
            out.force(false);
        } catch (IOException failed) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw failed;
        }
        closeQuietly();
        try {
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException notSupported) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        }
        index.clear();
        index.putAll(rebuilt);
        endOffset = position;
        liveBytes = 0L;
        for (Entry entry : index.values()) liveBytes += entry.recordBytes();
        deadBytes = 0L;
    }

    synchronized void clear() throws IOException {
        closeQuietly();
        index.clear();
        liveBytes = 0L;
        deadBytes = 0L;
        Files.deleteIfExists(path);
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            channel = null;
        }
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

    private static int crcOf(int x, int z, long stamp, int length, byte[] payload) {
        CRC32 crc = new CRC32();
        ByteBuffer head = ByteBuffer.allocate(20);
        head.putInt(x).putInt(z).putLong(stamp).putInt(length);
        crc.update(head.array(), 0, 20);
        crc.update(payload, 0, payload.length);
        return (int) crc.getValue();
    }
}
