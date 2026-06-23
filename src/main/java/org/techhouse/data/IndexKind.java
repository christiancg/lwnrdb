package org.techhouse.data;

import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonNull;

// The complete set of field index types. Each kind maps to the on-disk index type label used in the
// {collection}-{field}-{type}.idx file name. Scalar kinds (NUMBER, STRING, BOOLEAN, NULL) hold their
// values inline; OBJECT and ARRAY hold a SHA-256 hash of the whole value for element-match lookups
// and are kept in separate files. Custom types are user-defined and therefore dynamic (one label per
// registered type, the type's class simple name), so they are not enumerable here; see fileLabel.
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

    // Maps a stored index value's Java class to its on-disk index file label. Numbers are always
    // labeled NUMBER (documents store integral values as Integer and non-integral as Double); custom
    // types are dynamic and fall back to the class simple name.
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
