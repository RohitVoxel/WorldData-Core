package net.wdc.core.world.storage;

public final class PackedPos {

    private PackedPos() {}

    public static final int MASK4 = 0xF;
    public static final int POSITION_BITS_MASK = 0xFFF;

    public static int pack(int localX, int localY, int localZ) {
        validate(localX, "localX");
        validate(localY, "localY");
        validate(localZ, "localZ");
        return (localY << 8) | (localZ << 4) | localX;
    }

    public static int unpackX(int packed) {
        return packed & MASK4;
    }

    public static int unpackZ(int packed) {
        return (packed >>> 4) & MASK4;
    }

    public static int unpackY(int packed) {
        return (packed >>> 8) & MASK4;
    }

    public static boolean isInRange(int local) {
        return local >= 0 && local <= 15;
    }

    private static void validate(int local, String name) {
        if (!isInRange(local)) {
            throw new IllegalArgumentException(name + " must be in [0, 15], got " + local);
        }
    }
}
