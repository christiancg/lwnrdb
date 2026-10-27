package org.techhouse.simplejs.values;

import static org.techhouse.simplejs.values.JsObject.PropertyFlags.HIDDEN;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

// name/length/prototype are materialised on demand, never stored, so a delete has to be recorded:
// OrdinaryProperties.metadataKey trusts isDeleted as the only source of truth for those three keys.
public final class CallableMetadata {
    private PropertyTable table;
    private Set<String> deletedKeys;

    public PropertyTable table() {
        var existing = table;
        if (existing == null) {
            existing = new PropertyTable();
            table = existing;
        }
        return existing;
    }

    public boolean hasTable() {
        return table != null;
    }

    public void setProperty(String key, JsValue value) {
        final var own = table();
        own.defineValue(key, value);
        own.setFlags(key, HIDDEN);
    }

    public void setEnumerableProperty(String key, JsValue value) {
        table().set(key, value);
    }

    public JsValue getProperty(String key) {
        final var own = table;
        return own != null && own.has(key) ? own.get(key) : null;
    }

    public boolean hasProperty(String key) {
        final var own = table;
        return own != null && own.has(key);
    }

    public boolean deleteProperty(String key) {
        final var own = table;
        return own == null || own.delete(key);
    }

    public List<String> propertyKeys() {
        final var own = table;
        return own == null ? new ArrayList<>() : new ArrayList<>(own.keys());
    }

    public List<String> enumerablePropertyKeys() {
        final var own = table;
        return own == null ? List.of() : own.keys().stream().filter(own::isEnumerable).toList();
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
