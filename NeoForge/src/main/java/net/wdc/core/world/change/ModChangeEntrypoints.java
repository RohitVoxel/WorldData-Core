package net.wdc.core.world.change;

import net.wdc.core.api.changes.WDCChangeEntrypoint;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class ModChangeEntrypoints {

    private ModChangeEntrypoints() {}

    public static List<WDCChangeEntrypoint> discover(Consumer<Throwable> onFailure) {
        ClassLoader loader = ModChangeEntrypoints.class.getClassLoader();
        List<WDCChangeEntrypoint> found = new ArrayList<>();
        for (IModInfo mod : ModList.get().getMods()) {
            found.addAll(instantiate(mod.getModId(), mod.getModProperties(), loader, onFailure));
        }
        return found;
    }

    static List<WDCChangeEntrypoint> instantiate(String modId, Map<String, Object> properties, ClassLoader loader,
                                                   Consumer<Throwable> onFailure) {
        List<WDCChangeEntrypoint> created = new ArrayList<>();
        if (properties == null) return created;
        Object declared = properties.get(WDCChangeEntrypoint.ENTRYPOINT_KEY);
        if (declared == null) return created;
        Collection<?> classNames = declared instanceof Collection<?> collection ? collection : List.of(declared);
        for (Object className : classNames) {
            try {
                created.add(create(modId, className, loader));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                onFailure.accept(failure);
            }
        }
        return created;
    }

    private static WDCChangeEntrypoint create(String modId, Object className, ClassLoader loader)
            throws ReflectiveOperationException {
        if (!(className instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException("Mod '" + modId + "' declares an invalid " + WDCChangeEntrypoint.ENTRYPOINT_KEY
                    + " entry: " + className);
        }
        Class<?> type = Class.forName(name.trim(), true, loader);
        if (!WDCChangeEntrypoint.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("Mod '" + modId + "' entrypoint " + type.getName() + " does not implement "
                    + WDCChangeEntrypoint.class.getName());
        }
        return (WDCChangeEntrypoint) type.getDeclaredConstructor().newInstance();
    }
}
