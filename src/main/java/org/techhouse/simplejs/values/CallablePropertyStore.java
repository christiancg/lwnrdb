package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Shared own-property storage for both callable types, so JsFunction and JsNativeFunction answer
// JsCallableProperties identically.
final class CallablePropertyStore implements JsCallableProperties {
    private Map<String, JsValue> properties;
    private Set<String> enumerableKeys;
    private Set<String> deletedMetadataKeys;

    @Override
    public void setProperty(String key, JsValue value) {
        if (properties == null) {
            properties = new LinkedHashMap<>();
        }
        properties.put(key, value);
    }

    @Override
    public void setEnumerableProperty(String key, JsValue value) {
        setProperty(key, value);
        if (enumerableKeys == null) {
            enumerableKeys = new LinkedHashSet<>();
        }
        enumerableKeys.add(key);
    }

    @Override
    public JsValue getProperty(String key) {
        return properties == null ? null : properties.get(key);
    }

    @Override
    public boolean hasProperty(String key) {
        return properties != null && properties.containsKey(key);
    }

    @Override
    public boolean deleteProperty(String key) {
        if (properties != null) {
            properties.remove(key);
        }
        if (enumerableKeys != null) {
            enumerableKeys.remove(key);
        }
        return true;
    }

    @Override
    public void markMetadataDeleted(String key) {
        if (deletedMetadataKeys == null) {
            deletedMetadataKeys = new LinkedHashSet<>();
        }
        deletedMetadataKeys.add(key);
    }

    @Override
    public boolean isMetadataDeleted(String key) {
        return deletedMetadataKeys != null && deletedMetadataKeys.contains(key);
    }

    @Override
    public List<String> propertyKeys() {
        return properties == null ? List.of() : new ArrayList<>(properties.keySet());
    }

    @Override
    public List<String> enumerablePropertyKeys() {
        return enumerableKeys == null ? List.of() : new ArrayList<>(enumerableKeys);
    }
}
