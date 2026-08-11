package org.techhouse.simplejs.values;

import java.util.List;
import java.util.function.BiFunction;

public final class JsNativeFunction extends JsValue implements JsCallableProperties {
    private final String name;
    private final BiFunction<JsValue, List<JsValue>, JsValue> implementation;
    private final CallablePropertyStore properties = new CallablePropertyStore();
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

    @Override
    public void setProperty(String key, JsValue value) {
        properties.setProperty(key, value);
    }

    @Override
    public void setEnumerableProperty(String key, JsValue value) {
        properties.setEnumerableProperty(key, value);
    }

    @Override
    public JsValue getProperty(String key) {
        return properties.getProperty(key);
    }

    @Override
    public boolean hasProperty(String key) {
        return properties.hasProperty(key);
    }

    @Override
    public boolean deleteProperty(String key) {
        return properties.deleteProperty(key);
    }

    @Override
    public List<String> propertyKeys() {
        return properties.propertyKeys();
    }

    @Override
    public List<String> enumerablePropertyKeys() {
        return properties.enumerablePropertyKeys();
    }

    public JsObject getPrototype() {
        return prototype;
    }

    public void setPrototype(JsObject prototype) {
        this.prototype = prototype;
    }
}
