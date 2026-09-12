package org.techhouse.data;

import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonNull;

public enum IndexKind {
    NUMBER(Globals.INDEX_TYPE_NUMBER), STRING(Globals.INDEX_TYPE_STRING), BOOLEAN(Globals.INDEX_TYPE_BOOLEAN), NULL(
            Globals.INDEX_TYPE_NULL), OBJECT(Globals.INDEX_TYPE_OBJECT), ARRAY(Globals.INDEX_TYPE_ARRAY);

    private final String label;

    IndexKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
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
