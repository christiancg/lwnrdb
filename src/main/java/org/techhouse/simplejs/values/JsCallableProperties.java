package org.techhouse.simplejs.values;

import java.util.List;

// Own data properties on a callable. Functions are ordinary objects, so `f.helper = …` must stick.
// Builtin statics are installed through setProperty (non-enumerable, matching the spec and
// Environment.declareBuiltin); script assignments go through setEnumerableProperty, so
// Object.keys(f) reports what a script added without leaking the builtin surface.
public interface JsCallableProperties {
    // Builtin statics are non-enumerable but writable and configurable, the spec shape for a
    // function's own builtin surface.
    JsObject.PropertyFlags HIDDEN = new JsObject.PropertyFlags(true, false, true);

    void setProperty(String key, JsValue value);

    void setEnumerableProperty(String key, JsValue value);

    JsValue getProperty(String key);

    boolean hasProperty(String key);

    boolean deleteProperty(String key);

    void markMetadataDeleted(String key);

    boolean isMetadataDeleted(String key);

    List<String> propertyKeys();

    List<String> enumerablePropertyKeys();
}
