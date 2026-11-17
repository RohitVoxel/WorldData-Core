package net.wdc.core.world.spatial;

final class SpatialInterpolator {

    private SpatialInterpolator() {}

    static SpatialEnvironmentalState bilinear(SpatialProfile p00, SpatialProfile p10,
                                              SpatialProfile p01, SpatialProfile p11,
                                              float tx, float tz) {
        float elevation = blend(p00.elevation(), p10.elevation(), p01.elevation(), p11.elevation(), tx, tz);
        float slope = blend(p00.slopeRadians(), p10.slopeRadians(), p01.slopeRadians(), p11.slopeRadians(), tx, tz);
        float relief = blend(p00.localRelief(), p10.localRelief(), p01.localRelief(), p11.localRelief(), tx, tz);
        float enclosure = blend(p00.enclosure(), p10.enclosure(), p01.enclosure(), p11.enclosure(), tx, tz);
        float exposure = blend(p00.exposure(), p10.exposure(), p01.exposure(), p11.exposure(), tx, tz);

        float aspect = blendCircular(
                p00.aspectRadians(), p10.aspectRadians(), p01.aspectRadians(), p11.aspectRadians(), tx, tz);

        Landform landform = nearest(p00.landform(), p10.landform(), p01.landform(), p11.landform(), tx, tz);

        DirectionalExposure directional = new DirectionalExposure(
                blend(p00.directionalExposure().east(), p10.directionalExposure().east(),
                        p01.directionalExposure().east(), p11.directionalExposure().east(), tx, tz),
                blend(p00.directionalExposure().southeast(), p10.directionalExposure().southeast(),
                        p01.directionalExposure().southeast(), p11.directionalExposure().southeast(), tx, tz),
                blend(p00.directionalExposure().south(), p10.directionalExposure().south(),
                        p01.directionalExposure().south(), p11.directionalExposure().south(), tx, tz),
                blend(p00.directionalExposure().southwest(), p10.directionalExposure().southwest(),
                        p01.directionalExposure().southwest(), p11.directionalExposure().southwest(), tx, tz),
                blend(p00.directionalExposure().west(), p10.directionalExposure().west(),
                        p01.directionalExposure().west(), p11.directionalExposure().west(), tx, tz),
                blend(p00.directionalExposure().northwest(), p10.directionalExposure().northwest(),
                        p01.directionalExposure().northwest(), p11.directionalExposure().northwest(), tx, tz),
                blend(p00.directionalExposure().north(), p10.directionalExposure().north(),
                        p01.directionalExposure().north(), p11.directionalExposure().north(), tx, tz),
                blend(p00.directionalExposure().northeast(), p10.directionalExposure().northeast(),
                        p01.directionalExposure().northeast(), p11.directionalExposure().northeast(), tx, tz));

        WaterProximity water = blendWater(p00.waterProximity(), p10.waterProximity(),
                p01.waterProximity(), p11.waterProximity(), tx, tz);

        return new SpatialEnvironmentalState(
                elevation, slope, aspect, landform, relief, enclosure, exposure, directional, water);
    }

    private static float blend(float v00, float v10, float v01, float v11, float tx, float tz) {
        float top = v00 * (1f - tx) + v10 * tx;
        float bottom = v01 * (1f - tx) + v11 * tx;
        return top * (1f - tz) + bottom * tz;
    }

    private static float blendCircular(float a00, float a10, float a01, float a11, float tx, float tz) {
        float sin = blend((float) Math.sin(a00), (float) Math.sin(a10), (float) Math.sin(a01), (float) Math.sin(a11), tx, tz);
        float cos = blend((float) Math.cos(a00), (float) Math.cos(a10), (float) Math.cos(a01), (float) Math.cos(a11), tx, tz);
        float angle = (float) Math.atan2(sin, cos);
        return angle < 0f ? angle + (float) (2.0 * Math.PI) : angle;
    }

    private static Landform nearest(Landform l00, Landform l10, Landform l01, Landform l11, float tx, float tz) {
        if (tz < 0.5f) return tx < 0.5f ? l00 : l10;
        return tx < 0.5f ? l01 : l11;
    }

    private static WaterProximity blendWater(WaterProximity w00, WaterProximity w10,
                                             WaterProximity w01, WaterProximity w11, float tx, float tz) {
        WaterProximity[] corners = {w00, w10, w01, w11};
        float[] weights = {(1f - tx) * (1f - tz), tx * (1f - tz), (1f - tx) * tz, tx * tz};

        float strength = w00.influenceStrength() * weights[0] + w10.influenceStrength() * weights[1]
                + w01.influenceStrength() * weights[2] + w11.influenceStrength() * weights[3];

        float presentWeightSum = 0f;
        float weightedDistance = 0f;
        float sinSum = 0f;
        float cosSum = 0f;
        for (int i = 0; i < 4; i++) {
            if (!corners[i].present()) continue;
            presentWeightSum += weights[i];
            weightedDistance += corners[i].nearestDistanceBlocks() * weights[i];
            sinSum += (float) Math.sin(corners[i].directionRadians()) * weights[i];
            cosSum += (float) Math.cos(corners[i].directionRadians()) * weights[i];
        }

        if (presentWeightSum <= 0f) return WaterProximity.NONE;

        float distance = weightedDistance / presentWeightSum;
        float direction = (float) Math.atan2(sinSum, cosSum);
        if (direction < 0f) direction += (float) (2.0 * Math.PI);

        return new WaterProximity(true, distance, strength, direction);
    }
}
