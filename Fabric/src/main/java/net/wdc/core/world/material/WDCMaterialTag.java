package net.wdc.core.world.material;

public record WDCMaterialTag(WDCMaterial material, boolean isFluid) {

    public WDCMaterialTag {
        if (material == null) throw new IllegalArgumentException("material must not be null");
    }

    public static WDCMaterialTag of(WDCMaterial material) {
        return new WDCMaterialTag(material, material.isFluid());
    }

    public static WDCMaterialTag unclassifiedFluid() {
        return new WDCMaterialTag(WDCMaterial.UNKNOWN, true);
    }
}
