package net.wdc.core.world.material;

import net.wdc.core.world.storage.BlockStateKey;

public final class WDCFluidState {

    private static final WDCFluidInfo WATERLOGGED = new WDCFluidInfo(WDCFluidInfo.Kind.WATER, true, 8, false);

    private WDCFluidState() {}

    public static boolean isWaterlogged(BlockStateKey key) {
        return key != null && "true".equals(key.properties().get("waterlogged"));
    }

    public static WDCFluidInfo infoOf(BlockStateKey key) {
        if (key == null) return WDCFluidInfo.NONE;
        WDCMaterial material = WDCMaterialClassifier.classify(key.blockId());
        if (!material.isFluid()) return isWaterlogged(key) ? WATERLOGGED : WDCFluidInfo.NONE;
        WDCFluidInfo.Kind kind = material == WDCMaterial.WATER ? WDCFluidInfo.Kind.WATER
                : material == WDCMaterial.LAVA ? WDCFluidInfo.Kind.LAVA : WDCFluidInfo.Kind.OTHER;
        int level = parseLevel(key.properties().get("level"));
        boolean source = level == 0;
        boolean falling = level >= 8;
        int amount = source || falling ? 8 : 8 - level;
        return new WDCFluidInfo(kind, source, amount, falling);
    }

    public static int levelOf(BlockStateKey key) {
        return infoOf(key).level();
    }

    public static boolean isSource(BlockStateKey key) {
        return infoOf(key).source();
    }

    public static boolean isFlowing(BlockStateKey key) {
        WDCFluidInfo info = infoOf(key);
        return info.present() && !info.source();
    }

    private static int parseLevel(String raw) {
        if (raw == null) return 0;
        try {
            int parsed = Integer.parseInt(raw);
            return parsed < 0 || parsed > 15 ? 0 : parsed;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
