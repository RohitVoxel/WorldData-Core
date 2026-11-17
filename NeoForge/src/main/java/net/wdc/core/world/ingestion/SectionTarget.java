package net.wdc.core.world.ingestion;

import net.wdc.core.world.storage.BlockToken;
import net.wdc.core.world.storage.SectionBuilder;

public final class SectionTarget {

    public enum Kind { NONE, EMPTY, UNIFORM, MIXED, UNCHANGED }

    private final SectionBuilder builder = new SectionBuilder();
    private Kind kind = Kind.NONE;
    private int uniformToken;
    private long expected;
    private long fingerprint;

    public void reset() {
        kind = Kind.NONE;
        uniformToken = BlockToken.AIR;
        expected = 0L;
        fingerprint = 0L;
    }

    public void expect(long knownFingerprint) {
        expected = knownFingerprint;
    }

    public long expected() {
        return expected;
    }

    public long fingerprint() {
        return fingerprint;
    }

    public void setFingerprint(long value) {
        fingerprint = value;
    }

    public void setUnchanged(long value) {
        kind = Kind.UNCHANGED;
        fingerprint = value;
    }

    public Kind kind() {
        return kind;
    }

    public int uniformToken() {
        return uniformToken;
    }

    public SectionBuilder builder() {
        return builder;
    }

    public SectionBuilder beginMixed() {
        builder.reset();
        return builder;
    }

    public void setEmpty() {
        kind = Kind.EMPTY;
        uniformToken = BlockToken.AIR;
    }

    public void setUniform(int token) {
        kind = BlockToken.isDefaultAir(token) ? Kind.EMPTY : Kind.UNIFORM;
        uniformToken = token;
    }

    public void setMixed() {
        kind = Kind.MIXED;
    }

    public void discard() {
        kind = Kind.NONE;
    }
}
