package org.techhouse.simplejs.values;

import java.util.List;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.nodes.JsNode;

public final class JsFunction extends JsValue {
    private final String name;
    private final List<JsNode> params;
    private final JsNode body;
    private final boolean arrow;
    private final boolean expressionBody;
    private final boolean async;
    private final boolean generator;
    private final Environment closure;
    private JsObject prototype;

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
}
