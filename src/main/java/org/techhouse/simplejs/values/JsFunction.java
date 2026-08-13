package org.techhouse.simplejs.values;

import java.util.List;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.nodes.JsNode;

public final class JsFunction extends JsValue implements JsCallableProperties {
    private final String name;
    private final List<JsNode> params;
    private final JsNode body;
    private final boolean arrow;
    private final boolean expressionBody;
    private final boolean async;
    private final boolean generator;
    private final Environment closure;
    private final CallablePropertyStore properties = new CallablePropertyStore();
    private JsObject prototype;
    // Concise methods and accessors are not constructors, so they have no `prototype` property.
    private boolean method;

    public JsFunction(String name, List<JsNode> params, JsNode body, boolean arrow, boolean expressionBody,
            boolean async, boolean generator, Environment closure) {
        this.name = name;
        this.params = params;
        this.body = body;
        this.arrow = arrow;
        this.expressionBody = expressionBody;
        this.async = async;
        this.generator = generator;
        this.closure = closure;
    }

    public String getName() {
        return name;
    }

    public List<JsNode> getParams() {
        return params;
    }

    public JsNode getBody() {
        return body;
    }

    public boolean isArrow() {
        return arrow;
    }

    public boolean isExpressionBody() {
        return expressionBody;
    }

    public boolean isAsync() {
        return async;
    }

    public boolean isGenerator() {
        return generator;
    }

    public boolean isMethod() {
        return method;
    }

    public void markMethod() {
        this.method = true;
    }

    public Environment getClosure() {
        return closure;
    }

    public JsObject getPrototype() {
        if (prototype == null) {
            prototype = new JsObject();
            prototype.set("constructor", this);
        }
        return prototype;
    }

    public void setPrototype(JsObject prototype) {
        this.prototype = prototype;
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
    public void markMetadataDeleted(String key) {
        properties.markMetadataDeleted(key);
    }

    @Override
    public boolean isMetadataDeleted(String key) {
        return properties.isMetadataDeleted(key);
    }

    @Override
    public List<String> propertyKeys() {
        return properties.propertyKeys();
    }

    @Override
    public List<String> enumerablePropertyKeys() {
        return properties.enumerablePropertyKeys();
    }
}
