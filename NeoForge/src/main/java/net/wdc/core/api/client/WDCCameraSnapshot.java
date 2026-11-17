package net.wdc.core.api.client;

public record WDCCameraSnapshot(
        double x, double y, double z,
        double lookX, double lookY, double lookZ,
        float yawDegrees, float pitchDegrees,
        float fovRadians,
        long frameSequence
) {

    public int blockX() { return (int) Math.floor(x); }
    public int blockY() { return (int) Math.floor(y); }
    public int blockZ() { return (int) Math.floor(z); }

    public int chunkX() { return blockX() >> 4; }
    public int chunkZ() { return blockZ() >> 4; }
    public int sectionY() { return blockY() >> 4; }
}
