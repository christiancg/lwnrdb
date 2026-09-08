package org.techhouse.simplejs.values;

import java.util.List;
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
    private final CallableMetadata metadata = new CallableMetadata();
    private JsValue prototype;
    private String sourceText;
    private String moduleName;
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

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    public String getName() {
        return name;
    }

    public void setInferredName(String inferred) {
        if (name == null) {
            name = inferred;
        }
    }

    public String getSourceText() {
        return sourceText;
    }

    public void setSourceText(String sourceText) {
        this.sourceText = sourceText;
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

    public JsValue getPrototype() {
        if (prototype == null) {
            final var created = new JsObject();
            if (!generator) {
                created.defineValue("constructor", this);
                created.setFlags("constructor", JsObject.PropertyFlags.HIDDEN);
            }
            prototype = created;
        }
        return prototype;
    }

    public void setPrototype(JsValue prototype) {
        this.prototype = prototype;
    }

    @Override
    public CallableMetadata callableMetadata() {
        return metadata;
    }

    @Override
    public PropertyTable ownProperties() {
        return metadata.table();
    }

    @Override
    public boolean deleteOwnProperty(JsValue key) {
        return switch (metadata.deleteOwn(key, _ -> (isConstructor() || isGenerator()))) {
            case DELETED -> true;
            case REJECTED -> false;
            case ORDINARY -> super.deleteOwnProperty(key);
        };
    }

    @Override
    public List<String> enumerablePropertyKeys() {
        return metadata.table().keys().stream().filter(metadata.table()::isEnumerable).toList();
    }
}
