package org.techhouse.simplejs.values;

import static org.techhouse.simplejs.values.JsObject.PropertyFlags.HIDDEN;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

// name/length/prototype are materialised on demand, never stored, so a delete has to be recorded:
// OrdinaryProperties.metadataKey trusts isDeleted as the only source of truth for those three keys.
public final class CallableMetadata {
    private final PropertyTable table = new PropertyTable();
    private Set<String> deletedKeys;

    public PropertyTable table() {
        return table;
    }

    public void setProperty(String key, JsValue value) {
        table.defineValue(key, value);
        table.setFlags(key, HIDDEN);
    }

    public void setEnumerableProperty(String key, JsValue value) {
        table.set(key, value);
    }

    public JsValue getProperty(String key) {
        return table.has(key) ? table.get(key) : null;
    }

    public boolean hasProperty(String key) {
        return table.has(key);
    }

    public boolean deleteProperty(String key) {
        return table.delete(key);
    }

    public void markDeleted(String key) {
        if (deletedKeys == null) {
            deletedKeys = new LinkedHashSet<>();
        }
        deletedKeys.add(key);
    }

    public boolean isDeleted(String key) {
        return deletedKeys != null && deletedKeys.contains(key);
    }

    public Deletion deleteOwn(JsValue key, Predicate<String> retainsPrototype) {
        if (key instanceof JsSymbol) {
            return Deletion.ORDINARY;
        }
        final var name = OrdinaryProperties.keyName(key);
        if (("name".equals(name) || "length".equals(name)) && !hasProperty(name)) {
            markDeleted(name);
            return Deletion.DELETED;
        }
        if ("prototype".equals(name) && !hasProperty(name) && retainsPrototype.test(name)) {
            return Deletion.REJECTED;
        }
        return Deletion.ORDINARY;
    }

    public enum Deletion {
        DELETED, REJECTED, ORDINARY
    }
}
