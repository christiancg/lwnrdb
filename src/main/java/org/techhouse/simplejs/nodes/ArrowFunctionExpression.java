package org.techhouse.simplejs.nodes;

import java.util.List;

public class ArrowFunctionExpression extends Expression {
    private final List<Identifier> params;
    private final JsNode body;
    private final boolean expressionBody;

    public ArrowFunctionExpression(List<Identifier> params, JsNode body, boolean expressionBody) {
        this.params = params;
        this.body = body;
        this.expressionBody = expressionBody;
    }

    public List<Identifier> getParams() {
        return params;
    }

    public JsNode getBody() {
        return body;
    }

    public boolean isExpressionBody() {
        return expressionBody;
    }
}
