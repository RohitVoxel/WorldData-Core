package net.wdc.core.debug.monitor;

public enum MonitorApi {
    CHUNK(Group.ENTRY, "requests().chunk"),
    PEEK(Group.ENTRY, "requests().peek"),
    AVAILABILITY(Group.ENTRY, "requests().availability"),
    DRAIN_WAITING(Group.ENTRY, "requests().drainWaiting"),
    STATE_ID_AT(Group.POINT, "stateIdAt"),
    IS_PRESENT(Group.POINT, "isPresent"),
    BLOCK_AT(Group.POINT, "blockAt"),
    MATERIAL_AT(Group.POINT, "materialAt"),
    WATER_AT(Group.POINT, "waterAt"),
    IS_SUBMERGED(Group.POINT, "isSubmerged"),
    HAS_TAG_AT(Group.POINT, "hasTagAt"),
    MATCHES_AT(Group.POINT, "matchesAt"),
    BIOME(Group.DERIVED, "biome"),
    HEIGHTMAP(Group.DERIVED, "heightmap"),
    COLUMN(Group.DERIVED, "column"),
    BLOCK_INDEX(Group.DERIVED, "blockIndex"),
    OCCUPANCY(Group.DERIVED, "occupancy"),
    RAW(Group.DERIVED, "raw"),
    FIND_BLOCKS(Group.SCAN, "findBlocks"),
    VISIT_BLOCKS(Group.SCAN, "visitBlocks"),
    HAS_BLOCK(Group.SCAN, "hasBlock"),
    COUNT_BLOCKS(Group.SCAN, "countBlocks"),
    FIND_BLOCKS_BY_TAG(Group.SCAN, "findBlocksByTag"),
    FIND_BLOCKS_BY_NAME(Group.SCAN, "findBlocksByName"),
    FIND_BLOCKS_BY_STATE_ID(Group.SCAN, "findBlocksByStateId"),
    COUNT_BLOCKS_BY_TAG(Group.SCAN, "countBlocksByTag"),
    COUNT_BLOCKS_BY_NAME(Group.SCAN, "countBlocksByName"),
    COUNT_BLOCKS_BY_STATE_ID(Group.SCAN, "countBlocksByStateId"),
    HAS_BLOCK_BY_TAG(Group.SCAN, "hasBlockByTag"),
    HAS_BLOCK_BY_NAME(Group.SCAN, "hasBlockByName"),
    HAS_BLOCK_BY_STATE_ID(Group.SCAN, "hasBlockByStateId"),
    SUMMARIZE_BOX(Group.SPATIAL, "spatial().summarizeBox"),
    SUMMARIZE_SPHERE(Group.SPATIAL, "spatial().summarizeSphere"),
    VISIT_BOX(Group.SPATIAL, "spatial().visitBox"),
    VISIT_SPHERE(Group.SPATIAL, "spatial().visitSphere"),
    VISIT_COLUMN(Group.SPATIAL, "spatial().visitColumn"),
    SAMPLE_GRID(Group.SPATIAL, "spatial().sampleGrid"),
    LIGHT_AT(Group.LIGHT, "light().lightAt"),
    LIGHT_READ(Group.LIGHT, "light().read"),
    WORLD_SNAPSHOT(Group.WORLD, "world().snapshot"),
    STATE_ID_OF(Group.BLOCKS, "blocks().stateIdOf"),
    STATE_IDS_OF_BLOCK(Group.BLOCKS, "blocks().stateIdsOfBlock"),
    STATE_IDS_OF_TAG(Group.BLOCKS, "blocks().stateIdsOfTag"),
    BLOCK_INFO(Group.BLOCKS, "blocks().info"),
    BLOCK_ID_OF(Group.BLOCKS, "blocks().blockIdOf"),
    PROPERTIES_OF(Group.BLOCKS, "blocks().propertiesOf"),
    HAS_TAG(Group.BLOCKS, "blocks().hasTag");

    public enum Group {
        ENTRY("chunk entry"),
        POINT("point read"),
        DERIVED("derived layer"),
        SCAN("filter scan"),
        SPATIAL("spatial"),
        LIGHT("light"),
        WORLD("world"),
        BLOCKS("block lookup");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final int COUNT = values().length;

    private static final MonitorApi[] ALL = values();

    private final Group group;
    private final String label;

    MonitorApi(Group group, String label) {
        this.group = group;
        this.label = label;
    }

    public Group group() {
        return group;
    }

    public String label() {
        return label;
    }

    public static MonitorApi of(int ordinal) {
        return ALL[ordinal];
    }
}
