package net.wdc.core.world.ingestion;

import net.wdc.core.camera.CameraSnapshot;
import net.wdc.core.world.governor.GovernorPriority;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

public final class ClientChunkPriorityHint {

    private static final double NEAR_DISTANCE_BLOCKS = 48.0;

    private ClientChunkPriorityHint() {}

    public static GovernorPriority forChunk(CameraSnapshot camera, ChunkPos pos, double blocksPerSecond) {
        if (camera == null) return GovernorPriority.REQUEST_PRIORITY;

        Vec3 chunkCenter = new Vec3(pos.getMiddleBlockX(), camera.position().y, pos.getMiddleBlockZ());
        Vec3 toChunk = chunkCenter.subtract(camera.position());
        double distance = toChunk.length();

        if (distance <= NEAR_DISTANCE_BLOCKS) {
            return GovernorPriority.VISIBLE;
        }

        if (distance > 0.001) {
            double dot = toChunk.normalize().dot(camera.lookDirection());
            boolean fastMovement = blocksPerSecond >= 5.0;
            if (dot > 0.4 && fastMovement) {
                return GovernorPriority.MOVEMENT_DIRECTION;
            }
        }

        return GovernorPriority.DISTANCE;
    }
}
