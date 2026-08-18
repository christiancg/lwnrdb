package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.nodes.JsNode;

public final class JsFunction extends JsValue implements JsCallableProperties {
    private String name;
    private final List<JsNode> params;
    private final JsNode body;
    private final boolean arrow;
    private final boolean expressionBody;
    private final boolean async;
    private final boolean generator;
    private final Environment closure;
    private final PropertyTable table = new PropertyTable();
    private Set<String> deletedMetadataKeys;
    private JsValue prototype;
    // Concise methods and accessors are not constructors, so they have no `prototype` property.
    private boolean method;
    private boolean derivedConstructor;

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

    public void setInferredName(String inferred) {
        if (name == null) {
            name = inferred;
        }
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

    public boolean isDerivedConstructor() {
        return derivedConstructor;
    }

    public void markDerivedConstructor() {
        derivedConstructor = true;
    }

    public boolean isMethod() {
        return method;
    }

    public boolean isConstructor() {
        return !arrow && !async && !generator && !method;
    }

    public void markMethod() {
        this.method = true;
    }

    public Environment getClosure() {
        return closure;
    }

    // Properties of Generator/AsyncGenerator Function Instances: unlike an ordinary function's own
    // `prototype` (which back-links `.constructor` to the function itself), a generator function's
    // `prototype` object has no own properties at all - the `constructor` back-link instead lives on
    // the shared %GeneratorPrototype%/%AsyncGeneratorPrototype%, set up once in makeFunction.
    public JsValue getPrototype() {
        if (prototype == null) {
            final var created = new JsObject();
            if (!generator) {
                created.set("constructor", this);
            }
            prototype = created;
        }
        return prototype;
    }

    public void setPrototype(JsValue prototype) {
        this.prototype = prototype;
    }

    @Override
    public PropertyTable ownProperties() {
        return table;
    }

    @Override
    public void setProperty(String key, JsValue value) {
        table.defineValue(key, value);
        table.setFlags(key, HIDDEN);
    }

    @Override
    public void setEnumerableProperty(String key, JsValue value) {
        table.set(key, value);
    }

    @Override
    public JsValue getProperty(String key) {
        return table.has(key) ? table.get(key) : null;
    }

    @Override
    public boolean hasProperty(String key) {
        return table.has(key);
    }

    @Override
    public boolean deleteProperty(String key) {
        return table.delete(key);
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
        return new ArrayList<>(table.keys());
    }

    @Override
    public List<String> enumerablePropertyKeys() {
        return table.keys().stream().filter(table::isEnumerable).toList();
    }
}
