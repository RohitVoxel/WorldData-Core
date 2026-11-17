package net.wdc.core.debug.monitor;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class LoaderMods {

    private LoaderMods() {}

    public static void install() {
        WDCApiMonitor.installModLookup(new PathModLookup(LoaderMods::collect));
    }

    static Map<Path, String> collect() {
        Map<Path, String> found = new HashMap<>();
        ModList list = ModList.get();
        if (list == null) return found;
        for (IModInfo mod : list.getMods()) {
            Path file = fileOf(mod);
            if (file != null) found.putIfAbsent(file, mod.getModId());
        }
        return found;
    }

    static Path fileOf(Object mod) {
        try {
            Object info = invoke(mod, "getOwningFile");
            Object file = invoke(info, "getFile");
            Object direct = invokeOrNull(file, "getFilePath");
            if (direct instanceof Path path) return PathModLookup.normalize(path);
            Object jar = invokeOrNull(file, "getSecureJar");
            Object primary = invokeOrNull(jar, "getPrimaryPath");
            return primary instanceof Path path ? PathModLookup.normalize(path) : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static Object invokeOrNull(Object target, String name) {
        try {
            return invoke(target, name);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        if (target == null) throw new NoSuchMethodException(name);
        ArrayDeque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(target.getClass());
        while (!queue.isEmpty()) {
            Class<?> type = queue.poll();
            if (!seen.add(type)) continue;
            Method method = Modifier.isPublic(type.getModifiers()) ? publicMethod(type, name) : null;
            if (method != null) {
                try {
                    return method.invoke(target);
                } catch (IllegalAccessException blocked) {
                    method = null;
                }
            }
            if (type.getSuperclass() != null) queue.add(type.getSuperclass());
            for (Class<?> parent : type.getInterfaces()) queue.add(parent);
        }
        throw new NoSuchMethodException(name);
    }

    private static Method publicMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }
}
