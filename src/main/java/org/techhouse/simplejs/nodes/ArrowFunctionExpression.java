package org.techhouse.simplejs.nodes;

import java.util.List;

public class ArrowFunctionExpression extends Expression {
    private final List<JsNode> params;
    private final JsNode body;
    private final boolean expressionBody;
    private final boolean async;

    public ArrowFunctionExpression(List<JsNode> params, JsNode body, boolean expressionBody, boolean async) {
        this.params = params;
        this.body = body;
        this.expressionBody = expressionBody;
        this.async = async;
    }

    public List<JsNode> getParams() {
        return params;
    }

    public JsNode getBody() {
        return body;
    }

    public boolean isExpressionBody() {
        return expressionBody;
    }

    public boolean isAsync() {
        return async;
    }
}
