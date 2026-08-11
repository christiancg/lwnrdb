package org.techhouse.simplejs.values;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public final class JsNativeFunction extends JsValue {
    private final String name;
    private final BiFunction<JsValue, List<JsValue>, JsValue> implementation;
    private Map<String, JsValue> properties;
    private JsValue boundTarget;
    private List<JsValue> boundArgs;
    private JsObject prototype;

    public JsNativeFunction(String name, BiFunction<JsValue, List<JsValue>, JsValue> implementation) {
        this.name = name;
        this.implementation = implementation;
    }

    public String getName() {
        return name;
    }

    public void setBound(JsValue boundTarget, List<JsValue> boundArgs) {
        this.boundTarget = boundTarget;
        this.boundArgs = boundArgs;
    }

    public boolean isBound() {
        return boundTarget != null;
    }

    public JsValue getBoundTarget() {
        return boundTarget;
    }

    public List<JsValue> getBoundArgs() {
        return boundArgs;
    }

    public JsValue invoke(JsValue thisArg, List<JsValue> args) {
        return implementation.apply(thisArg, args);
    }

    public void setProperty(String key, JsValue value) {
        if (properties == null) {
            properties = new HashMap<>();
        }
        properties.put(key, value);
    }

    public JsValue getProperty(String key) {
        return properties == null ? null : properties.get(key);
    }

    public boolean hasProperty(String key) {
        return properties != null && properties.containsKey(key);
    }

    public JsObject getPrototype() {
        return prototype;
    }

    public void setPrototype(JsObject prototype) {
        this.prototype = prototype;
    }
}
