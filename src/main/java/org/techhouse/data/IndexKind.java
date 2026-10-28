package org.techhouse.data;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.config.Globals;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;

public enum IndexKind {
    NUMBER(Globals.INDEX_TYPE_NUMBER), STRING(Globals.INDEX_TYPE_STRING), BOOLEAN(Globals.INDEX_TYPE_BOOLEAN), NULL(
            Globals.INDEX_TYPE_NULL), OBJECT(Globals.INDEX_TYPE_OBJECT), ARRAY(Globals.INDEX_TYPE_ARRAY);

    public static final List<IndexKind> HASH_KINDS = List.of(OBJECT, ARRAY);

    private final String label;

    IndexKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static List<Class<?>> scalarIndexClasses() {
        final var classes = new ArrayList<Class<?>>(List.of(Number.class, Boolean.class, String.class));
        classes.addAll(CustomTypeFactory.getCustomTypes().values());
        return classes;
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
