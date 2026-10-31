package org.techhouse.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.techhouse.config.Globals;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;

public enum IndexKind {
    NUMBER(Globals.INDEX_TYPE_NUMBER), STRING(Globals.INDEX_TYPE_STRING), BOOLEAN(Globals.INDEX_TYPE_BOOLEAN), NULL(
            Globals.INDEX_TYPE_NULL), OBJECT(Globals.INDEX_TYPE_OBJECT), ARRAY(Globals.INDEX_TYPE_ARRAY);

    public static final List<IndexKind> HASH_KINDS = List.of(OBJECT, ARRAY);

    private static volatile ScalarClasses scalarClasses;

    private final String label;

    IndexKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    private record ScalarClasses(String[] customNames, Class<?>[] customClasses, List<Class<?>> classes) {
        static ScalarClasses of(Map<String, Class<? extends JsonCustom<?>>> registry) {
            final var names = new ArrayList<String>(registry.size());
            final var customs = new ArrayList<Class<?>>(registry.size());
            for (final var custom : registry.entrySet()) {
                names.add(custom.getKey());
                customs.add(custom.getValue());
            }
            final var classes = new ArrayList<Class<?>>(List.of(Number.class, Boolean.class, String.class));
            classes.addAll(customs);
            return new ScalarClasses(names.toArray(String[]::new), customs.toArray(Class<?>[]::new),
                    List.copyOf(classes));
        }

        boolean describes(Map<String, Class<? extends JsonCustom<?>>> registry) {
            if (registry.size() != customNames.length) {
                return false;
            }
            for (var i = 0; i < customNames.length; i++) {
                if (registry.get(customNames[i]) != customClasses[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    public static List<Class<?>> scalarIndexClasses() {
        final var registry = CustomTypeFactory.getCustomTypes();
        var cached = scalarClasses;
        if (cached == null || !cached.describes(registry)) {
            cached = ScalarClasses.of(registry);
            scalarClasses = cached;
        }
        return cached.classes();
    }

    public static Class<?> scalarIndexClassOf(Object value) {
        return switch (value) {
            case Number _ -> Number.class;
            case Boolean _ -> Boolean.class;
            case String _ -> String.class;
            case JsonCustom<?> custom -> CustomTypeFactory.getCustomTypes().get(custom.getCustomTypeName());
            default -> throw new IllegalStateException("Unexpected value: " + value);
        };
    }

    public static String fileLabel(Class<?> valueClass) {
        if (Number.class.isAssignableFrom(valueClass)) {
            return NUMBER.label;
        }
        if (valueClass == Boolean.class) {
            return BOOLEAN.label;
        }
        if (valueClass == String.class) {
            return STRING.label;
        }
        if (JsonNull.class.isAssignableFrom(valueClass)) {
            return NULL.label;
        }
        return valueClass.getSimpleName();
    }
}
