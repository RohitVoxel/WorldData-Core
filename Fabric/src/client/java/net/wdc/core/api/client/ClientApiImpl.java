package net.wdc.core.api.client;

import net.wdc.core.camera.CameraManager;
import net.wdc.core.camera.CameraSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public final class ClientApiImpl implements ClientApi {

    @Override
    public Optional<WDCCameraSnapshot> camera() {
        CameraSnapshot snapshot = CameraManager.get();
        if (snapshot == null) return Optional.empty();

        double lookX = snapshot.lookDirection().x;
        double lookY = snapshot.lookDirection().y;
        double lookZ = snapshot.lookDirection().z;
        float yaw = (float) Math.toDegrees(Math.atan2(-lookX, lookZ));
        float pitch = (float) Math.toDegrees(Math.asin(-lookY));

        return Optional.of(new WDCCameraSnapshot(
                snapshot.position().x, snapshot.position().y, snapshot.position().z,
                lookX, lookY, lookZ,
                yaw, pitch,
                snapshot.fovRadians(),
                snapshot.frameSequence()
        ));
    }

    @Override
    @SuppressWarnings("resource")
    public Optional<WDCPlayerSnapshot> player() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return Optional.empty();

        Vec3 eyePos = player.getEyePosition();
        Vec3 velocity = player.getDeltaMovement();

        return Optional.of(new WDCPlayerSnapshot(
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(),
                eyePos.x, eyePos.y, eyePos.z,
                player.getYRot(), player.getXRot(),
                player.getHealth(),
                player.onGround(),
                player.isSprinting(),
                player.isShiftKeyDown(),
                player.isUnderWater(),
                velocity.length()
        ));
    }
}
