package net.wdc.core.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.wdc.core.config.WDCConfig;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.WDCCacheProvider;
import net.wdc.core.world.ingestion.IngestionBridge;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class HotOverlayRenderer {

    private static final float[] NORMAL = {1.0f, 0.9f, 0.1f, 0.8f};
    private static final float[] AUTO_HOT = {0.1f, 1.0f, 0.2f, 0.7f};
    private static final float[] PINNED = {0.1f, 1.0f, 0.2f, 1.0f};

    private HotOverlayRenderer() {}

    public static void render(PoseStack poseStack, Camera camera) {
        if (!HotOverlay.enabled() || poseStack == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        WDCCache cache = WDCCacheProvider.getIfPresent();
        if (cache == null) return;
        Vec3 eye = camera.getPosition();
        String dimension = IngestionBridge.dimensionName(minecraft.level.dimension());
        List<HotOverlay.Box> boxes = HotOverlay.collect(cache, dimension,
                ((int) Math.floor(eye.x)) >> 4, ((int) Math.floor(eye.z)) >> 4, ((int) Math.floor(eye.y)) >> 4,
                WDCConfig.get().hot.overlayRadiusChunks, HotOverlay.DEFAULT_SECTION_RADIUS);
        if (boxes.isEmpty()) return;
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        poseStack.pushPose();
        poseStack.translate(-eye.x, -eye.y, -eye.z);
        for (HotOverlay.Box box : boxes) {
            float[] color = box.pinned() ? PINNED : box.hot() ? AUTO_HOT : NORMAL;
            double minX = box.chunkX() * 16.0;
            double minY = box.sectionY() * 16.0;
            double minZ = box.chunkZ() * 16.0;
            LevelRenderer.renderLineBox(poseStack, lines, minX, minY, minZ, minX + 16.0, minY + 16.0, minZ + 16.0,
                    color[0], color[1], color[2], color[3]);
        }
        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
