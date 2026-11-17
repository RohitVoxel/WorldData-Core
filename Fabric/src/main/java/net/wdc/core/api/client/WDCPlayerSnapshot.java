package net.wdc.core.api.client;

public record WDCPlayerSnapshot(
        String dimensionId,
        double x, double y, double z,
        double eyeX, double eyeY, double eyeZ,
        float yawDegrees, float pitchDegrees,
        float health,
        boolean onGround,
        boolean sprinting,
        boolean sneaking,
        boolean underwater,
        double speed
) {

    public int blockX() { return (int) Math.floor(x); }
    public int blockY() { return (int) Math.floor(y); }
    public int blockZ() { return (int) Math.floor(z); }

    public int eyeBlockX() { return (int) Math.floor(eyeX); }
    public int eyeBlockY() { return (int) Math.floor(eyeY); }
    public int eyeBlockZ() { return (int) Math.floor(eyeZ); }

    public int chunkX() { return blockX() >> 4; }
    public int chunkZ() { return blockZ() >> 4; }
}
