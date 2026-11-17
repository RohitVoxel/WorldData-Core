package net.wdc.core.camera;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

public record CameraSnapshot(
        Vec3 position,
        Vec3 lookDirection,
        Matrix4f projectionMatrix,
        Matrix4f viewMatrix,
        Frustum frustum,
        float fovRadians,
        float nearPlane,
        float farPlane,
        float partialTick,
        long frameSequence
) {

    public static Matrix4f buildViewMatrix(net.minecraft.client.Camera camera) {
        Matrix4f view = new Matrix4f();
        view.rotate(camera.rotation().invert(new Quaternionf()));
        return view;
    }
}
