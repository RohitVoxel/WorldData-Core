package net.wdc.core.world.storage;

public final class BlockToken {

    public static final int AIR = 0;
    public static final int STATE_ID_MASK = 0x00FFFFFF;
    public static final int MAX_STATES = STATE_ID_MASK;
    public static final int FLAG_NON_AIR = 1 << 24;
    public static final int FLAG_MOTION_BLOCKING = 1 << 25;
    public static final int FLAG_HAS_FLUID = 1 << 26;
    public static final int FLAG_OPAQUE = 1 << 27;
    public static final int FLAG_EMISSIVE = 1 << 28;
    public static final int FLAG_CANOPY = 1 << 29;
    public static final int FLAG_BLOCK_ENTITY = 1 << 30;
    public static final int FLAG_CUSTOM_MATERIAL = 1 << 31;
    public static final int FLAG_MASK = 0xFF000000;

    private BlockToken() {}

    public static int make(int globalId, int flags) {
        if (globalId < 0) return AIR;
        if (globalId >= MAX_STATES) throw new IllegalArgumentException("global state id out of token range: " + globalId);
        return (globalId + 1) | (flags & FLAG_MASK) | FLAG_NON_AIR;
    }

    public static int makeAirLike(int globalId, int flags) {
        if (globalId < 0) return AIR;
        if (globalId >= MAX_STATES) throw new IllegalArgumentException("global state id out of token range: " + globalId);
        return (globalId + 1) | (flags & FLAG_MASK & ~FLAG_NON_AIR);
    }

    public static int globalIdOf(int token) {
        return (token & STATE_ID_MASK) - 1;
    }

    public static boolean isAir(int token) {
        return (token & FLAG_NON_AIR) == 0;
    }

    public static boolean isDefaultAir(int token) {
        return (token & STATE_ID_MASK) == 0;
    }

    public static boolean sameState(int a, int b) {
        return ((a ^ b) & STATE_ID_MASK) == 0;
    }

    public static boolean has(int token, int flag) {
        return (token & flag) != 0;
    }

    public static int bloomBit1(int token) {
        return token & 255;
    }

    public static int bloomBit2(int token) {
        return (token >>> 8) & 255;
    }
}
