package net.wdc.core.world.cache;

public record SectionFingerprint(
        int sectionY,
        boolean empty,
        int distinctStates,
        int nonEmptyBlockCount,
        long contentHash,
        int tickingBlockCount,
        int tickingFluidCount
) {

    public static SectionFingerprint empty(int sectionY) {
        return new SectionFingerprint(sectionY, true, 0, 0, 0L, 0, 0);
    }

    public boolean matches(SectionFingerprint other) {
        if (other == null) return false;
        if (this.sectionY != other.sectionY) return false;
        if (this.empty || other.empty) return this.empty == other.empty;
        return this.distinctStates == other.distinctStates
                && this.nonEmptyBlockCount == other.nonEmptyBlockCount
                && this.contentHash == other.contentHash
                && this.tickingBlockCount == other.tickingBlockCount
                && this.tickingFluidCount == other.tickingFluidCount;
    }
}
