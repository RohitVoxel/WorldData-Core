package net.wdc.core.api.internal;

import net.wdc.core.debug.WDCMetrics;
import net.wdc.core.world.work.CoreThreads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;

public final class ServerLightPump {

    public static final int MIN_REQUESTED_PER_TICK = 8;

    private static final int[][] CHECK_POINTS = {{0, 0, 0}, {15, 15, 15}, {8, 7, 8}, {3, 12, 14}};
    private static final int[][] PROBE_POINTS = {{0, 8, 0}, {15, 8, 15}, {7, 8, 7}, {0, 15, 15}, {15, 15, 0}, {8, 15, 8}, {3, 12, 14}, {12, 4, 2}};

    private static final byte[] SCRATCH = new byte[4096];

    private ServerLightPump() {}

    public static int pump(MinecraftServer server, LightSnapshots snapshots, long budgetNanos) {
        CoreThreads.requireNotWorker("minecraft light read");
        long start = System.nanoTime();
        long gameTick = server.getTickCount();
        snapshots.advance(gameTick);
        for (ServerLevel level : server.getAllLevels()) publishClock(snapshots, level);
        int built = 0;
        int requested = 0;
        while (true) {
            boolean overBudget = System.nanoTime() - start >= budgetNanos;
            if (overBudget && !(snapshots.hasRequested() && requested < MIN_REQUESTED_PER_TICK)) break;
            LightSnapshots.SectionKey key = snapshots.poll();
            if (key == null) break;
            requested++;
            if (build(server, snapshots, key, gameTick)) built++;
        }
        return built;
    }

    private static void publishClock(LightSnapshots snapshots, ServerLevel level) {
        boolean hasSky = level.dimensionType().hasSkyLight();
        float timeOfDay = hasSky ? level.dimensionType().timeOfDay(level.getDayTime()) : 0.0F;
        snapshots.publishClock(DimensionIds.nameOf(level.dimension()),
                new LightSnapshots.Clock(timeOfDay, level.getRainLevel(1.0F), level.getThunderLevel(1.0F), hasSky));
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimensionId) {
        try {
            return server.getLevel(DimensionIds.keyOf(dimensionId));
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    private static boolean build(MinecraftServer server, LightSnapshots snapshots, LightSnapshots.SectionKey key, long gameTick) {
        ServerLevel level = levelOf(server, key.dimensionId());
        if (level == null || key.sectionY() < level.getMinSection() || key.sectionY() >= level.getMaxSection()
                || level.getChunkSource().getChunkNow(key.sectionX(), key.sectionZ()) == null) {
            snapshots.putMissing(key, gameTick);
            WDCMetrics.increment(WDCMetrics.Counter.LIGHT_SECTION_UNLOADED);
            return false;
        }
        int baseX = key.sectionX() << 4;
        int baseY = key.sectionY() << 4;
        int baseZ = key.sectionZ() << 4;
        LevelLightEngine engine = level.getLightEngine();
        SectionPos sectionPos = SectionPos.of(key.sectionX(), key.sectionY(), key.sectionZ());
        LightSnapshots.Layer sky = layerOf(level, engine, LightLayer.SKY, sectionPos, baseX, baseY, baseZ);
        LightSnapshots.Layer block = layerOf(level, engine, LightLayer.BLOCK, sectionPos, baseX, baseY, baseZ);
        WDCMetrics.increment(WDCMetrics.Counter.LIGHT_SECTION_BUILT);
        snapshots.putSection(key, LightSnapshots.Section.of(sky, block, gameTick));
        return true;
    }

    private static LightSnapshots.Layer layerOf(ServerLevel level, LevelLightEngine engine, LightLayer layer,
                                                SectionPos sectionPos, int baseX, int baseY, int baseZ) {
        if (layer == LightLayer.SKY && !level.dimensionType().hasSkyLight()) return LightSnapshots.Layer.uniform(0);
        DataLayer data = trustedLayer(level, engine, layer, sectionPos, baseX, baseY, baseZ);
        if (data != null) {
            WDCMetrics.increment(WDCMetrics.Counter.LIGHT_BUILD_FAST);
            if (data.isDefinitelyHomogenous()) return LightSnapshots.Layer.uniform(data.get(0, 0, 0));
            int index = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) SCRATCH[index++] = (byte) data.get(x, y, z);
                }
            }
            return LightSnapshots.Layer.ofValues(SCRATCH);
        }
        WDCMetrics.increment(WDCMetrics.Counter.LIGHT_BUILD_SLOW);
        return engineLayer(level, layer, baseX, baseY, baseZ);
    }

    private static DataLayer trustedLayer(ServerLevel level, LevelLightEngine engine, LightLayer layer, SectionPos sectionPos,
                                          int baseX, int baseY, int baseZ) {
        DataLayer data;
        try {
            data = engine.getLayerListener(layer).getDataLayerData(sectionPos);
        } catch (RuntimeException unsupported) {
            return null;
        }
        if (data == null) return null;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] point : CHECK_POINTS) {
            pos.set(baseX + point[0], baseY + point[1], baseZ + point[2]);
            if (data.get(point[0], point[1], point[2]) != level.getBrightness(layer, pos)) {
                WDCMetrics.increment(WDCMetrics.Counter.LIGHT_FASTPATH_MISMATCH);
                return null;
            }
        }
        return data;
    }

    private static LightSnapshots.Layer engineLayer(ServerLevel level, LightLayer layer, int baseX, int baseY, int baseZ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int first = -1;
        boolean uniform = true;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int value = level.getBrightness(layer, pos.set(baseX + x, baseY, baseZ + z));
                SCRATCH[(z << 4) | x] = (byte) value;
                if (first < 0) first = value;
                else if (value != first) uniform = false;
            }
        }
        if (uniform) {
            boolean probesAgree = true;
            for (int[] point : PROBE_POINTS) {
                if (level.getBrightness(layer, pos.set(baseX + point[0], baseY + point[1], baseZ + point[2])) != first) {
                    probesAgree = false;
                    break;
                }
            }
            if (probesAgree) return LightSnapshots.Layer.uniform(first);
        } else if (planesMatch(level, layer, baseX, baseY, baseZ, pos)) {
            for (int y = 1; y < 16; y++) System.arraycopy(SCRATCH, 0, SCRATCH, y << 8, 256);
            return LightSnapshots.Layer.ofValues(SCRATCH);
        }
        int index = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) SCRATCH[index++] = (byte) level.getBrightness(layer, pos.set(baseX + x, baseY + y, baseZ + z));
            }
        }
        return LightSnapshots.Layer.ofValues(SCRATCH);
    }

    private static boolean planesMatch(ServerLevel level, LightLayer layer, int baseX, int baseY, int baseZ,
                                       BlockPos.MutableBlockPos pos) {
        for (int y : new int[]{8, 15}) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (level.getBrightness(layer, pos.set(baseX + x, baseY + y, baseZ + z)) != SCRATCH[(z << 4) | x]) return false;
                }
            }
        }
        return true;
    }
}
