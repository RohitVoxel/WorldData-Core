package net.wdc.core.api.request;

import net.wdc.core.world.storage.WDCChunk;
import net.wdc.core.world.storage.WDCSection;

public final class WDCChunkView {

    private final WDCChunk chunk;

    WDCChunkView(WDCChunk chunk) {
        this.chunk = chunk;
    }

    public int chunkX() { return chunk.chunkX(); }

    public int chunkZ() { return chunk.chunkZ(); }

    public long version() { return chunk.version(); }

    public int sectionCount() { return chunk.sectionCount(); }

    public boolean hasSection(int sectionY) { return chunk.hasSection(sectionY); }

    public WDCSectionView getSection(int sectionY) {
        WDCSection section = chunk.getSection(sectionY);
        return section == null ? null : new WDCSectionView(section);
    }
}
