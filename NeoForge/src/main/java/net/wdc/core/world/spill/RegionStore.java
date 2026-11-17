package net.wdc.core.world.spill;

import java.io.IOException;
import java.util.List;

interface RegionStore extends AutoCloseable {

    byte[] get(int x, int z) throws IOException;

    boolean put(int x, int z, byte[] payload, long stamp) throws IOException;

    boolean remove(int x, int z, long stamp) throws IOException;

    boolean contains(int x, int z);

    long stampOf(int x, int z);

    int entryCount();

    List<long[]> snapshotKeysAndStamps();

    long fileBytes();

    long deadBytes();

    boolean wasDamaged();

    void maintain() throws IOException;

    @Override
    void close();
}
