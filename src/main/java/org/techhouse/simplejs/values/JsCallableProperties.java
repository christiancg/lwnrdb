package org.techhouse.simplejs.values;

import java.util.List;

// Own data properties on a callable. Functions are ordinary objects, so `f.helper = …` must stick.
// Builtin statics are installed through setProperty (non-enumerable, matching the spec and
// Environment.declareBuiltin); script assignments go through setEnumerableProperty, so
// Object.keys(f) reports what a script added without leaking the builtin surface.
public interface JsCallableProperties {
    void setProperty(String key, JsValue value);

    void setEnumerableProperty(String key, JsValue value);

    JsValue getProperty(String key);

    boolean hasProperty(String key);

    boolean deleteProperty(String key);

    List<String> propertyKeys();

    List<String> enumerablePropertyKeys();
}
