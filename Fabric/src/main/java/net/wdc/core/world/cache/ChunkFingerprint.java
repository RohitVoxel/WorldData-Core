package net.wdc.core.world.cache;

import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;

import java.util.Arrays;

public record ChunkFingerprint(
        String dimensionId,
        int chunkX,
        int chunkZ,
        long heightmapHash,
        long nonEmptySectionMask,
        SectionFingerprint[] sectionFingerprints,
        long blockEntitySummaryHash,
        long biomeSummaryHash,
        boolean complete
) {

    public static ChunkFingerprint unavailable(String dimensionId, int chunkX, int chunkZ) {
        return new ChunkFingerprint(dimensionId, chunkX, chunkZ, 0L, 0L,
                new SectionFingerprint[0], 0L, 0L, false);
    }

    public boolean isComplete() {
        return complete;
    }

    public boolean matches(ChunkFingerprint other) {
        if (other == null) return false;
        if (!this.complete || !other.complete) return false;
        if (!this.dimensionId.equals(other.dimensionId)) return false;
        if (this.chunkX != other.chunkX || this.chunkZ != other.chunkZ) return false;
        if (this.heightmapHash != other.heightmapHash) return false;
        if (this.nonEmptySectionMask != other.nonEmptySectionMask) return false;
        if (this.blockEntitySummaryHash != other.blockEntitySummaryHash) return false;
        if (this.biomeSummaryHash != other.biomeSummaryHash) return false;
        return sectionsMatch(other);
    }

    private boolean sectionsMatch(ChunkFingerprint other) {
        if (this.sectionFingerprints.length != other.sectionFingerprints.length) return false;

        for (int i = 0; i < this.sectionFingerprints.length; i++) {
            SectionFingerprint mine = this.sectionFingerprints[i];
            SectionFingerprint theirs = other.sectionFingerprints[i];
            if (mine.sectionY() != theirs.sectionY()) return false;
            if (!mine.matches(theirs)) return false;
        }
        return true;
    }

    public SectionFingerprint[] sectionFingerprints() {
        return Arrays.copyOf(sectionFingerprints, sectionFingerprints.length);
    }

    public static final class Builder {
        private final String dimensionId;
        private final int chunkX;
        private final int chunkZ;
        private final Int2ObjectRBTreeMap<SectionFingerprint> sections = new Int2ObjectRBTreeMap<>();
        private long heightmapHash;
        private long blockEntitySummaryHash;
        private long biomeSummaryHash;
        private boolean complete = true;

        public Builder(String dimensionId, int chunkX, int chunkZ) {
            this.dimensionId = dimensionId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        public Builder heightmapHash(long value) {
            this.heightmapHash = value;
            return this;
        }

        public Builder blockEntitySummaryHash(long value) {
            this.blockEntitySummaryHash = value;
            return this;
        }

        public Builder biomeSummaryHash(long value) {
            this.biomeSummaryHash = value;
            return this;
        }

        public Builder putSection(SectionFingerprint fingerprint) {
            sections.put(fingerprint.sectionY(), fingerprint);
            return this;
        }

        public Builder markIncomplete() {
            this.complete = false;
            return this;
        }

        public ChunkFingerprint build() {
            if (!complete) {
                return ChunkFingerprint.unavailable(dimensionId, chunkX, chunkZ);
            }

            SectionFingerprint[] ordered = sections.values().toArray(new SectionFingerprint[0]);
            long mask = 0L;
            int bit = 0;
            for (SectionFingerprint sf : ordered) {
                if (!sf.empty() && bit < 64) mask |= (1L << bit);
                bit++;
            }

            return new ChunkFingerprint(dimensionId, chunkX, chunkZ, heightmapHash, mask,
                    ordered, blockEntitySummaryHash, biomeSummaryHash, true);
        }
    }
}
