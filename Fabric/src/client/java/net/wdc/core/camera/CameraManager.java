package net.wdc.core.camera;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class CameraManager {

    private CameraManager() {}

    private static final AtomicReference<CameraSnapshot> CURRENT = new AtomicReference<>(null);
    private static final AtomicLong FRAME_SEQUENCE = new AtomicLong(0L);

    public static void publish(Camera camera, Matrix4f projection, Frustum frustum, float tickDelta) {
        long frame = FRAME_SEQUENCE.incrementAndGet();

        float fov = (float) (2.0 * Math.atan2(1.0, projection.m11()));
        float nearPlane = projection.perspectiveNear();
        float farPlane = projection.perspectiveFar();

        Matrix4f viewMatrix = CameraSnapshot.buildViewMatrix(camera);

        Vector3f lookJoml = camera.getLookVector();
        Vec3 lookVec = new Vec3(lookJoml.x(), lookJoml.y(), lookJoml.z());

        CameraSnapshot snapshot = new CameraSnapshot(
                camera.getPosition(),
                lookVec,
                projection,
                viewMatrix,
                frustum,
                fov,
                nearPlane,
                farPlane,
                tickDelta,
                frame
        );

        CURRENT.set(snapshot);
    }

    public static CameraSnapshot get() {
        return CURRENT.get();
    }

    public static void reset() {
        CURRENT.set(null);
    }
}
