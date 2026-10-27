package org.techhouse.simplejs.values;

import java.util.List;

public interface JsCallableProperties {
    CallableMetadata callableMetadata();

    default void setProperty(String key, JsValue value) {
        callableMetadata().setProperty(key, value);
    }

    default void setEnumerableProperty(String key, JsValue value) {
        callableMetadata().setEnumerableProperty(key, value);
    }

    default JsValue getProperty(String key) {
        return callableMetadata().getProperty(key);
    }

    default boolean hasProperty(String key) {
        return callableMetadata().hasProperty(key);
    }

    default boolean deleteProperty(String key) {
        return callableMetadata().deleteProperty(key);
    }

    default void markMetadataDeleted(String key) {
        callableMetadata().markDeleted(key);
    }

    default boolean isMetadataDeleted(String key) {
        return callableMetadata().isDeleted(key);
    }

    default List<String> propertyKeys() {
        return callableMetadata().propertyKeys();
    }

    default List<String> enumerablePropertyKeys() {
        return callableMetadata().enumerablePropertyKeys();
    }
}
