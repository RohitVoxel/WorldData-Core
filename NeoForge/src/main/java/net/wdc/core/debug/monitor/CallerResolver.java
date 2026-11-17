package net.wdc.core.debug.monitor;

import java.util.Iterator;
import java.util.stream.Stream;

public final class CallerResolver {

    public static final String INTERNAL = "worlddata_core (internal)";
    public static final String UNKNOWN = "(unknown)";
    public static final String MOD_PREFIX = "mod:";
    public static final String CLASS_PREFIX = "class:";

    enum Kind { SKIP, CORE, CALLER }

    private static final String[] SKIPPED = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "net.minecraft.", "com.mojang.", "net.fabricmc.",
            "net.neoforged.", "cpw.mods.", "org.spongepowered.", "org.objectweb.", "it.unimi.", "org.slf4j.",
            "com.google.", "org.apache.", "net.wdc.core.api.", "net.wdc.core.debug.monitor."
    };

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static volatile ModLookup lookup = ModLookup.NONE;

    private static final ClassValue<Kind> KINDS = new ClassValue<>() {
        @Override
        protected Kind computeValue(Class<?> type) {
            return classify(type.getName());
        }
    };

    private static final ClassValue<String> KEYS = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            return keyOf(type);
        }
    };

    private CallerResolver() {}

    public static void setLookup(ModLookup value) {
        lookup = value == null ? ModLookup.NONE : value;
    }

    static Kind classify(String className) {
        for (String prefix : SKIPPED) {
            if (className.startsWith(prefix)) return Kind.SKIP;
        }
        if (className.startsWith("net.wdc.core.")) return Kind.CORE;
        return Kind.CALLER;
    }

    static String keyOf(Class<?> type) {
        String modId = null;
        try {
            modId = lookup.modIdOf(type);
        } catch (RuntimeException | LinkageError failure) {
            modId = null;
        }
        if (modId != null && !modId.isEmpty()) return MOD_PREFIX + modId;
        return CLASS_PREFIX + outerName(type.getName());
    }

    static String outerName(String className) {
        int lambda = className.indexOf("$$");
        String trimmed = lambda >= 0 ? className.substring(0, lambda) : className;
        int nested = trimmed.indexOf('$');
        return nested > 0 ? trimmed.substring(0, nested) : trimmed;
    }

    public static String current() {
        return WALKER.walk(CallerResolver::pick);
    }

    private static String pick(Stream<StackWalker.StackFrame> frames) {
        Iterator<StackWalker.StackFrame> iterator = frames.iterator();
        while (iterator.hasNext()) {
            Class<?> type = iterator.next().getDeclaringClass();
            switch (KINDS.get(type)) {
                case SKIP:
                    break;
                case CORE:
                    return INTERNAL;
                default:
                    return KEYS.get(type);
            }
        }
        return UNKNOWN;
    }
}
