package net.wdc.core.api;

import net.wdc.core.api.capabilities.WDCCapability;
import net.wdc.core.api.capabilities.CapabilitiesApi;
import net.wdc.core.api.changes.ChangesApi;
import net.wdc.core.api.client.ClientApi;
import net.wdc.core.api.coords.CoordsApi;
import net.wdc.core.api.extensions.ExtensionRegistry;
import net.wdc.core.api.extensions.ExtensionRegistryProvider;
import net.wdc.core.api.internal.ClientApiRegistry;
import net.wdc.core.api.internal.WorldAuthority;
import net.wdc.core.api.internal.ClientFirstLightSource;
import net.wdc.core.api.internal.LightSource;
import net.wdc.core.api.internal.WorldStateSource;
import net.wdc.core.api.request.BlocksApi;
import net.wdc.core.api.request.RequestsApi;
import net.wdc.core.api.spatial.SpatialApi;
import net.wdc.core.api.world.LightApi;
import net.wdc.core.api.world.WorldApi;
import net.wdc.core.world.cache.WDCCache;
import net.wdc.core.world.cache.WDCCacheProvider;
import net.wdc.core.world.change.WorldChangeTrackerProvider;
import net.wdc.core.world.request.RequestManager;
import net.wdc.core.world.request.RequestManagerProvider;
import net.wdc.core.world.ingestion.LoadedChunkProbe;
import net.wdc.core.world.ingestion.LoadedChunkProbeProvider;
import net.wdc.core.world.storage.GlobalBlockStateRegistry;
import net.wdc.core.world.storage.GlobalBlockStateRegistryProvider;

import java.util.EnumSet;
import java.util.Set;

public final class WDC {

    private static volatile WorldStateSource serverWorldStateSource;
    private static volatile WorldStateSource testOverride;
    private static volatile LightSource serverLightSource;
    private static volatile LightSource lightTestOverride;
    private static volatile LightApi lightApi;
    private static volatile ClientFirstLightSource clientFirstLightSource;

    private WDC() {}

    public static WorldApi world() {
        return new WorldApi(resolveWorldStateSource());
    }

    public static LightApi light() {
        LightSource source = resolveLightSource();
        LightApi cached = lightApi;
        if (cached != null && cached.source() == source) return cached;
        LightApi created = new LightApi(source);
        lightApi = created;
        return created;
    }

    public static ClientApi client() {
        Object clientApi = ClientApiRegistry.getOrNull();
        if (clientApi == null) {
            throw new IllegalStateException("WDC.client() is only available on the client, or the client hasn't finished initializing yet");
        }
        return (ClientApi) clientApi;
    }

    private record RequestsBinding(WDCCache cache, GlobalBlockStateRegistry registry, LoadedChunkProbe probe,
                                   RequestsApi requests, SpatialApi spatial, BlocksApi blocks) {}

    private static volatile RequestsBinding requestsBinding;

    private static RequestsBinding binding() {
        WDCCache cache = WDCCacheProvider.getIfPresent();
        GlobalBlockStateRegistry registry = GlobalBlockStateRegistryProvider.getOrCreate();
        LoadedChunkProbe probe = LoadedChunkProbeProvider.getIfPresent();
        RequestsBinding current = requestsBinding;
        if (current != null && current.cache == cache && current.registry == registry && current.probe == probe) return current;
        RequestsApi requests = new RequestsApi(cache, registry, probe);
        RequestsBinding created = new RequestsBinding(cache, registry, probe, requests,
                new SpatialApi(requests::chunk, requests::peek, registry), new BlocksApi(registry));
        requestsBinding = created;
        return created;
    }

    public static RequestsApi requests() {
        return binding().requests;
    }

    public static BlocksApi blocks() {
        return binding().blocks;
    }

    public static ChangesApi changes() {
        return new ChangesApi(WorldChangeTrackerProvider.getOrCreate(), WorldChangeTrackerProvider.rescanner(),
                WDCCacheProvider.getIfPresent());
    }

    public static SpatialApi spatial() {
        return binding().spatial;
    }

    public static CapabilitiesApi capabilities() {
        Set<WDCCapability> available = EnumSet.noneOf(WDCCapability.class);
        RequestManager rm = RequestManagerProvider.getIfPresent();
        WDCCache cache = WDCCacheProvider.getIfPresent();
        if (rm != null && cache != null) {
            available.add(WDCCapability.CHUNK_REQUESTS);
            available.add(WDCCapability.BLOCK_QUERIES);
            available.add(WDCCapability.COLUMN_CACHE);
            available.add(WDCCapability.WATER_DATA);
            available.add(WDCCapability.BIOME_DATA);
            available.add(WDCCapability.BLOCK_ACCESS);
            available.add(WDCCapability.HEIGHT_KINDS);
            available.add(WDCCapability.DISK_SPILL);
            available.add(WDCCapability.SPATIAL_QUERIES);
        }
        available.add(WDCCapability.CHANGE_EVENTS);
        available.add(WDCCapability.WORLD_STATE);
        available.add(WDCCapability.EXTENSION_REGISTRY);
        if (ClientApiRegistry.getOrNull() != null) {
            available.add(WDCCapability.CLIENT_STATE);
        }
        if (resolveLightSource() != LightSource.UNAVAILABLE) {
            available.add(WDCCapability.LIGHT_QUERY);
        }
        return new CapabilitiesApi(available);
    }

    public static CoordsApi coords() {
        return new CoordsApi();
    }

    public static ExtensionRegistry extensions() {
        return ExtensionRegistryProvider.getOrCreate();
    }

    public static void registerServerWorldStateSource(WorldStateSource source) {
        serverWorldStateSource = source;
    }

    public static void setWorldStateSourceForTesting(WorldStateSource source) {
        testOverride = source;
    }

    public static void registerServerLightSource(LightSource source) {
        serverLightSource = source;
    }

    public static void setLightSourceForTesting(LightSource source) {
        lightTestOverride = source;
    }

    private static WorldStateSource resolveWorldStateSource() {
        if (testOverride != null) return testOverride;
        if (WorldAuthority.isServerThread()) {
            WorldStateSource authoritative = serverWorldStateSource;
            if (authoritative != null) return authoritative;
        }
        if (ClientApiRegistry.getOrNull() != null) {
            WorldStateSource clientSource = ClientApiRegistry.currentWorldStateSource();
            if (clientSource != null) return clientSource;
        }
        WorldStateSource server = serverWorldStateSource;
        return server != null ? server : WorldStateSource.UNAVAILABLE;
    }

    private static LightSource resolveLightSource() {
        if (lightTestOverride != null) return lightTestOverride;
        if (WorldAuthority.isServerThread()) {
            LightSource authoritative = serverLightSource;
            if (authoritative != null) return authoritative;
        }
        LightSource client = ClientApiRegistry.getOrNull() != null ? ClientApiRegistry.currentLightSource() : null;
        LightSource fallback = serverLightSource;
        if (client != null && fallback != null) {
            ClientFirstLightSource combined = clientFirstLightSource;
            if (combined == null || !combined.uses(client, fallback)) {
                combined = new ClientFirstLightSource(client, fallback);
                clientFirstLightSource = combined;
            }
            return combined;
        }
        if (client != null) return client;
        LightSource server = serverLightSource;
        return server != null ? server : LightSource.UNAVAILABLE;
    }
}
