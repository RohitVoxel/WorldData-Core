package net.wdc.core.world.material;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class WDCMaterialClassifier {

    private static final Map<String, WDCMaterial> EXACT = buildExactTable();
    private static final Map<String, WDCMaterial> classificationCache = new ConcurrentHashMap<>();

    private WDCMaterialClassifier() {}

    public static WDCMaterial classify(String blockId) {
        if (blockId == null || blockId.isEmpty()) return WDCMaterial.UNKNOWN;
        return classificationCache.computeIfAbsent(blockId, WDCMaterialClassifier::classifyUncached);
    }

    private static WDCMaterial classifyUncached(String blockId) {
        WDCMaterial exact = EXACT.get(blockId);
        if (exact != null) return exact;

        String path = pathOf(blockId);

        if (path.endsWith("_leaves")) return WDCMaterial.LEAVES;
        if (path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_planks")
                || path.endsWith("_stem") || path.endsWith("_hyphae")) return WDCMaterial.WOOD;
        if (path.endsWith("_ice")) return WDCMaterial.ICE;
        if (path.endsWith("_ore")) return WDCMaterial.STONE;
        if (path.endsWith("_wool") || path.endsWith("_carpet")) return WDCMaterial.WOOL;
        if (path.endsWith("_glass") || path.endsWith("_glass_pane")) return WDCMaterial.GLASS;
        if (path.endsWith("_sapling") || path.endsWith("_seeds") || path.endsWith("_mushroom")
                || path.endsWith("_fungus") || path.endsWith("_bush")) return WDCMaterial.PLANT;
        if (path.startsWith("potted_")) return WDCMaterial.PLANT;

        return WDCMaterial.UNKNOWN;
    }

    private static String pathOf(String blockId) {
        int colon = blockId.indexOf(':');
        return colon < 0 ? blockId : blockId.substring(colon + 1);
    }

    private static Map<String, WDCMaterial> buildExactTable() {
        Map<String, WDCMaterial> table = new ConcurrentHashMap<>();

        put(table, WDCMaterial.AIR, "minecraft:air", "minecraft:cave_air", "minecraft:void_air");

        put(table, WDCMaterial.STONE, "minecraft:stone", "minecraft:cobblestone", "minecraft:deepslate",
                "minecraft:cobbled_deepslate", "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
                "minecraft:tuff", "minecraft:calcite", "minecraft:basalt", "minecraft:blackstone",
                "minecraft:bedrock", "minecraft:obsidian", "minecraft:netherrack", "minecraft:end_stone");

        put(table, WDCMaterial.DIRT, "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
                "minecraft:podzol", "minecraft:mycelium", "minecraft:mud", "minecraft:muddy_mangrove_roots",
                "minecraft:farmland", "minecraft:dirt_path");

        put(table, WDCMaterial.GRASS, "minecraft:grass_block", "minecraft:short_grass", "minecraft:tall_grass",
                "minecraft:fern", "minecraft:large_fern");

        put(table, WDCMaterial.SAND, "minecraft:sand", "minecraft:red_sand", "minecraft:suspicious_sand");

        put(table, WDCMaterial.GRAVEL, "minecraft:gravel", "minecraft:suspicious_gravel");

        put(table, WDCMaterial.CLAY, "minecraft:clay");

        put(table, WDCMaterial.ICE, "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:frosted_ice");

        put(table, WDCMaterial.SNOW, "minecraft:snow", "minecraft:snow_block", "minecraft:powder_snow");

        put(table, WDCMaterial.WATER, "minecraft:water", "minecraft:bubble_column", "minecraft:kelp",
                "minecraft:kelp_plant", "minecraft:seagrass", "minecraft:tall_seagrass");

        put(table, WDCMaterial.LAVA, "minecraft:lava");

        put(table, WDCMaterial.CROP, "minecraft:wheat", "minecraft:carrots", "minecraft:potatoes",
                "minecraft:beetroots", "minecraft:melon_stem", "minecraft:pumpkin_stem", "minecraft:sweet_berry_bush",
                "minecraft:nether_wart", "minecraft:cocoa", "minecraft:sugar_cane", "minecraft:bamboo");

        put(table, WDCMaterial.PLANT, "minecraft:dead_bush", "minecraft:vine", "minecraft:lily_pad",
                "minecraft:cactus", "minecraft:sea_pickle", "minecraft:glow_lichen", "minecraft:hanging_roots",
                "minecraft:moss_carpet", "minecraft:moss_block", "minecraft:big_dripleaf", "minecraft:small_dripleaf");

        put(table, WDCMaterial.METAL, "minecraft:iron_block", "minecraft:gold_block", "minecraft:copper_block",
                "minecraft:netherite_block", "minecraft:iron_bars", "minecraft:chain", "minecraft:anvil",
                "minecraft:lightning_rod");

        put(table, WDCMaterial.GLASS, "minecraft:glass", "minecraft:glass_pane", "minecraft:tinted_glass");

        return Map.copyOf(table);
    }

    private static void put(Map<String, WDCMaterial> table, WDCMaterial material, String... ids) {
        for (String id : Set.of(ids)) table.put(id, material);
    }
}
