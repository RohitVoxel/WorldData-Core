package net.wdc.core.world.identity;

import net.minecraft.core.BlockPos;

import java.util.concurrent.atomic.AtomicLong;

public final class IdentitySeedFactory {

    private IdentitySeedFactory() {}

    private static final long SESSION_SALT = fmix64(System.nanoTime());
    private static final AtomicLong COUNTER = new AtomicLong(0L);

    public static long newSeed() {
        long ordinal = COUNTER.incrementAndGet();
        return fmix64(SESSION_SALT ^ (ordinal * 0x9E3779B97F4A7C15L));
    }

    public static long fallbackSeed(BlockPos pos) {
        long h = 1469598103934665603L;
        h = 31 * h + pos.getX();
        h = 31 * h + pos.getY();
        h = 31 * h + pos.getZ();
        return fmix64(h);
    }

    public static long salted(long seed, String effectSalt) {
        long h = seed;
        h = 31 * h + effectSalt.hashCode();
        return fmix64(h);
    }

    public static float toUnitFloat(long mixed) {
        return ((mixed >>> 40) & 0xFFFFFFL) / (float) (1 << 24);
    }

    private static long fmix64(long h) {
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return h;
    }
}
