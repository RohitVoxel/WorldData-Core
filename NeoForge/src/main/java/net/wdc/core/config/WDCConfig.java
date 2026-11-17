package net.wdc.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.wdc.core.debug.monitor.WDCApiMonitor;
import net.wdc.core.world.governor.IngestionPerformanceConfig;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class WDCConfig {

    public static final int DEFAULT_MAX_STALE_CHUNKS_PER_DIMENSION = 1024;
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String[] OBSOLETE_SECTIONS = {"acquire", "remote"};

    private static volatile WDCConfig instance = new WDCConfig();

    public DebugConfig debug = new DebugConfig();
    public IngestionPerformanceConfig performance = new IngestionPerformanceConfig();
    public CapacityConfig capacity = new CapacityConfig();
    public BudgetConfig budget = new BudgetConfig();
    public CommandsConfig commands = new CommandsConfig();
    public MonitorConfig monitor = new MonitorConfig();
    public SessionConfig session = new SessionConfig();
    public HotSectionsConfig hot = new HotSectionsConfig();

    public static WDCConfig get() {
        return instance;
    }

    public static void load() {
        ensurePaths();
        Path file = path();
        Integer legacyLiveLimit = null;
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file)) {
                JsonElement root = JsonParser.parseReader(reader);
                List<String> obsolete = obsoleteSections(root);
                if (!obsolete.isEmpty()) {
                    LOGGER.info("WorldData: ignoring obsolete config section(s) {}; force-loading and remote chunk fetching were removed and the sections are dropped from the file now", obsolete);
                }
                WDCConfig loaded = GSON.fromJson(root, WDCConfig.class);
                instance = (loaded != null) ? loaded : new WDCConfig();
                legacyLiveLimit = legacyLiveLimit(root);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("WorldData: failed to read {}, using defaults — {}", CorePaths.CONFIG_FILE, e.getMessage());
                instance = new WDCConfig();
            }
        } else {
            instance = new WDCConfig();
        }
        if (instance.performance == null) instance.performance = new IngestionPerformanceConfig();
        instance.performance.migrateLegacy();
        Integer legacyShare = instance.performance.takeLegacyBudgetShare();
        if (instance.performance.clampToConfigCeilings()) {
            LOGGER.warn("WorldData: performance limits in worlddata_core.json exceeded the allowed ceilings and were clamped");
        }
        try {
            instance.performance.validate();
        } catch (IllegalArgumentException invalid) {
            LOGGER.warn("WorldData: invalid performance config, resetting to defaults — {}", invalid.getMessage());
            instance.performance = new IngestionPerformanceConfig();
        }
        if (instance.capacity == null) instance.capacity = new CapacityConfig();
        instance.capacity.migrateLegacy();
        if (instance.capacity.clampToCeilings()) {
            LOGGER.warn("WorldData: a capacity value in worlddata_core.json exceeded the allowed ceiling and was clamped");
        }
        try {
            instance.capacity.validate();
        } catch (IllegalArgumentException invalid) {
            LOGGER.warn("WorldData: invalid capacity config, resetting to defaults — {}", invalid.getMessage());
            instance.capacity = new CapacityConfig();
        }
        if (instance.performance.queueCapacity > instance.capacity.ingestQueueLengthMax) {
            instance.performance.queueCapacity = instance.capacity.ingestQueueLengthMax;
        }
        if (legacyLiveLimit != null) instance.capacity.liveMax = legacyLiveLimit;
        if (instance.capacity.liveMax > instance.capacity.liveMaxCeiling) {
            LOGGER.warn("WorldData: capacity.liveMax {} exceeded liveMaxCeiling {} and was clamped",
                    instance.capacity.liveMax, instance.capacity.liveMaxCeiling);
            instance.capacity.liveMax = instance.capacity.liveMaxCeiling;
        }
        if (instance.capacity.liveMax < 1) instance.capacity.liveMax = CapacityConfig.DEFAULT_LIVE_MAX;
        instance.performance.liveLimit = instance.capacity.liveMax;
        if (instance.budget == null) instance.budget = new BudgetConfig();
        if (legacyShare != null) instance.budget.ingestShareOfRemainingTickPercent = legacyShare;
        if (instance.budget.clamp()) {
            LOGGER.warn("WorldData: budget settings in worlddata_core.json were outside the allowed limits and were clamped");
        }
        if (instance.commands == null) instance.commands = new CommandsConfig();
        if (instance.commands.clamp()) {
            LOGGER.warn("WorldData: command permission levels in worlddata_core.json were outside 0..{} and were clamped", CommandsConfig.MAX_LEVEL);
        }
        if (instance.monitor == null) instance.monitor = new MonitorConfig();
        if (instance.monitor.clamp()) {
            LOGGER.warn("WorldData: monitor settings in worlddata_core.json were outside the allowed limits and were clamped");
        }
        WDCApiMonitor.applyConfig(instance.monitor);
        if (instance.session == null) instance.session = new SessionConfig();
        if (instance.debug == null) instance.debug = new DebugConfig();
        if (instance.hot == null) instance.hot = new HotSectionsConfig();
        if (instance.hot.clamp()) {
            LOGGER.warn("WorldData: hot section settings in worlddata_core.json were outside the allowed limits and were clamped");
        }
        save();
    }

    public static void ensurePaths() {
        if (!CorePaths.isInitialized()) CorePaths.init(platformConfigDirectory());
    }

    static Integer legacyLiveLimit(JsonElement root) {
        if (root == null || !root.isJsonObject()) return null;
        JsonObject object = root.getAsJsonObject();
        JsonElement capacity = object.get("capacity");
        if (capacity != null && capacity.isJsonObject() && capacity.getAsJsonObject().has("liveMax")) return null;
        JsonElement performance = object.get("performance");
        if (performance == null || !performance.isJsonObject()) return null;
        JsonElement live = performance.getAsJsonObject().get("liveLimit");
        if (live == null || !live.isJsonPrimitive() || !live.getAsJsonPrimitive().isNumber()) return null;
        return live.getAsInt();
    }

    static List<String> obsoleteSections(JsonElement root) {
        List<String> found = new ArrayList<>();
        if (root == null || !root.isJsonObject()) return found;
        JsonObject object = root.getAsJsonObject();
        for (String section : OBSOLETE_SECTIONS) {
            if (object.has(section)) found.add(section);
        }
        return found;
    }

    public static void save() {
        Path file = path();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (var writer = Files.newBufferedWriter(tmp)) {
                GSON.toJson(instance, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException notSupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("WorldData: failed to write {} — {}", CorePaths.CONFIG_FILE, e.getMessage());
        }
    }

    private static Path path() {
        ensurePaths();
        return CorePaths.configFile();
    }

    private static Path platformConfigDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }
}
