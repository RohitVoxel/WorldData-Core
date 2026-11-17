package net.wdc.core.world.storage;

import net.wdc.core.world.material.WDCFluidInfo;
import net.wdc.core.world.material.WDCMaterial;

public final class StateDescriptor {

    private static final WDCMaterial[] MATERIALS = WDCMaterial.values();

    private StateDescriptor() {}

    public static long pack(WDCMaterial material, int tagMask, WDCFluidInfo fluid, int emission, int opacity) {
        long out = material.ordinal() & 0xFFFFL;
        out |= (tagMask & 0xFFFFL) << 16;
        long fluidKind = fluid == null ? 0L : switch (fluid.kind()) {
            case NONE -> 0L;
            case WATER -> 1L;
            case LAVA -> 2L;
            case OTHER -> 3L;
        };
        out |= fluidKind << 32;
        if (fluid != null && fluid.present()) {
            long detail = Math.max(0, Math.min(15, fluid.amount()));
            if (fluid.source()) detail |= 1L << 4;
            if (fluid.falling()) detail |= 1L << 5;
            out |= detail << 40;
        }
        out |= (long) (emission & 15) << 48;
        out |= (long) (opacity & 15) << 52;
        return out;
    }

    public static WDCMaterial material(long descriptor) {
        int ordinal = (int) (descriptor & 0xFFFFL);
        return ordinal < MATERIALS.length ? MATERIALS[ordinal] : WDCMaterial.UNKNOWN;
    }

    public static int tagMask(long descriptor) {
        return (int) ((descriptor >>> 16) & 0xFFFFL);
    }

    public static int fluidKind(long descriptor) {
        return (int) ((descriptor >>> 32) & 0xFFL);
    }

    public static int fluidAmount(long descriptor) {
        return (int) ((descriptor >>> 40) & 15L);
    }

    public static WDCFluidInfo fluid(long descriptor) {
        WDCFluidInfo.Kind kind = switch (fluidKind(descriptor)) {
            case 1 -> WDCFluidInfo.Kind.WATER;
            case 2 -> WDCFluidInfo.Kind.LAVA;
            case 3 -> WDCFluidInfo.Kind.OTHER;
            default -> WDCFluidInfo.Kind.NONE;
        };
        if (kind == WDCFluidInfo.Kind.NONE) return WDCFluidInfo.NONE;
        return new WDCFluidInfo(kind, fluidSource(descriptor), fluidAmount(descriptor), fluidFalling(descriptor));
    }

    public static boolean fluidSource(long descriptor) {
        return ((descriptor >>> 44) & 1L) != 0L;
    }

    public static boolean fluidFalling(long descriptor) {
        return ((descriptor >>> 45) & 1L) != 0L;
    }

    public static int lightEmission(long descriptor) {
        return (int) ((descriptor >>> 48) & 15L);
    }

    public static int lightOpacity(long descriptor) {
        return (int) ((descriptor >>> 52) & 15L);
    }

    public static boolean hasTag(long descriptor, int tag) {
        return (tagMask(descriptor) & tag) != 0;
    }
}
